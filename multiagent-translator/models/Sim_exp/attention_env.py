"""
Sim_exp — live SML harness for the ICS attention 3-agent SOAR build.

Runs the three agents (environment, scenario, mission) in one Soar kernel and
wires their io-links:

    environment --[signal transition-mission]--> scenario  AND  mission
    scenario    --[scenario.attacker-id]-------> mission

SCHEDULING — the Train_gate concept
-----------------------------------
    kernel.RunAllAgents(5, sml_PHASE)   all three think, one decision cycle each
    _deliver()                          Python moves the messages AFTER

Because delivery runs after everyone, a WME written in cycle N is not visible to
its receiver until cycle N+1 — one cycle per hop. Nothing here simulates a delay;
that is simply what honest message passing costs. It also makes the kernel's
internal agent order irrelevant, since no agent can read another's output within
a cycle.

So mission STARTS (on ^signal) one cycle before it learns the attacker-id. That is
harmless: `initialize` only zeroes the clocks and points at person 0, and no
examine can be proposed until ^inspect-clock reaches ^inspection-time — which
costs a tick anyway. The deadline is not consumed while waiting; PRISM states the
same thing as the invariant `state_name=0 => state_wall_clock<=0`.

Message lifetime differs by kind:
    ^signal       one-shot   — destroyed after one cycle, like Train_gate's
    ^attacker-id  persistent — mission re-reads it on every one of the 20 examines

Nothing domain-specific is hardcoded beyond the experiment knobs
(deadline, use_box, box_accuracy) and the crowd size N=20.

Reference bridge: D:\\Rajarshi\\Courses\\MBSE\\Multi-agent Train_gate\\env\\environment.py
Ours keeps its scheduling and adds typed/nested WMEs plus a broadcast +
point-to-point topology.
"""

import os, sys, re

# Reuse the Train_gate SOAR SML runtime (no copy).
_SOAR_BIN = r"D:\Rajarshi\Courses\MBSE\Multi-agent Train_gate\SOAR\bin"
os.add_dll_directory(_SOAR_BIN)
sys.path.insert(0, _SOAR_BIN)
import Python_sml_ClientInterface as sml  # noqa: E402

# Our 3-agent SOAR build (each agent loads its own load.soar). Resolved relative to
# this file so a copied-out tree tests ITS OWN agents, not another checkout's.
_AGENTS = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       os.pardir, "ICS_SOAR-attention-3agent")

N_PEOPLE = 20                       # crowd size, ids 0..19
_TERMINAL = {"Complete", "Deadline", "Not-Tagged"}

# This harness records what the SOAR agents do and nothing else. It carries no
# analytic Pmin/Pmax reference: a closed form kept next to the simulation cannot
# check it — if the formula were wrong the two would agree anyway and hide it.
# Compare against PRISM by running PRISM and diffing the two tables.


class AttentionEnv:
    """One Soar kernel, three agents, hand-wired io-links."""

    def __init__(self, deadline=5, use_box=1, box_accuracy=0.8, inspection_time=1):
        self.deadline = deadline
        self.use_box = use_box
        self.box_accuracy = box_accuracy
        self.inspection_time = inspection_time     # Collins v1.5's I

        self.kernel = None
        self.environment = self.scenario = self.mission = None

        # dynamic (cross-agent) WMEs we place, so reset() can remove them
        self._signal_wmes = []          # ^signal delivered LAST cycle (one-shot)
        self._attacker_wme = None       # ^attacker-id on mission's ^scenario id
        self._mission_scenario_id = None  # mission input ^scenario identifier

        self._pending_signal = None     # set by the output handler, drained by _deliver
        self._attacker_delivered = False
        self.attacker_id = None         # captured pick, for the histogram

    # ── setup ────────────────────────────────────────────────────────────────

    def create_agents(self):
        self.kernel = sml.Kernel.CreateKernelInNewThread()
        self.environment = self.kernel.CreateAgent("environment")
        self.scenario = self.kernel.CreateAgent("scenario")
        self.mission = self.kernel.CreateAgent("mission")

        for agent, folder in ((self.environment, "environment"),
                              (self.scenario, "scenario"),
                              (self.mission, "mission")):
            path = os.path.join(_AGENTS, folder, "load.soar")
            agent.LoadProductions(path)
            if agent.HadError():
                raise RuntimeError(f"{folder} load error: {agent.GetLastErrorDescription()}")

        # ^signal is an EVENT, so catch it at the moment it is written. Polling the
        # output-link instead would re-send it every cycle, because the SOAR never
        # clears its own output-link once written.
        self.environment.AddOutputHandler("signal", self._on_env_signal, None)

        self._build_static_inputs()

    def _build_static_inputs(self):
        """Place the config + crowd that the environment normally supplies.

        These are client-owned input WMEs; they persist across InitSoar, so we
        build them once (not rebuilt in reset())."""
        # environment: systemdata.sys-start-mission (int>0) + environment.crowd (exists)
        env_il = self.environment.GetInputLink()
        sysdata = self.environment.CreateIdWME(env_il, "systemdata")
        self.environment.CreateIntWME(sysdata, "sys-start-mission", 1)
        env_env = self.environment.CreateIdWME(env_il, "environment")
        self.environment.CreateIdWME(env_env, "crowd")
        self.environment.Commit()

        # scenario: scenario.{use-box(int), box-accuracy(float)} + crowd of N persons
        sc_il = self.scenario.GetInputLink()
        sc_cfg = self.scenario.CreateIdWME(sc_il, "scenario")
        self.scenario.CreateIntWME(sc_cfg, "use-box", self.use_box)
        self.scenario.CreateFloatWME(sc_cfg, "box-accuracy", float(self.box_accuracy))
        sc_env = self.scenario.CreateIdWME(sc_il, "environment")
        sc_crowd = self.scenario.CreateIdWME(sc_env, "crowd")
        for i in range(N_PEOPLE):
            person = self.scenario.CreateIdWME(sc_crowd, "person")
            self.scenario.CreateIntWME(person, "id", i)
        self.scenario.Commit()

        # mission: scenario.{use-box, deadline, inspection-time}; attacker-id added
        # on delivery. inspection-time is what the apply rules advance ^wall-clock by
        # — omit it and no examine can ever fire (silent TIMEOUT, no error).
        mi_il = self.mission.GetInputLink()
        mi_cfg = self.mission.CreateIdWME(mi_il, "scenario")
        self.mission.CreateIntWME(mi_cfg, "use-box", self.use_box)
        self.mission.CreateIntWME(mi_cfg, "deadline", self.deadline)
        self.mission.CreateIntWME(mi_cfg, "inspection-time", self.inspection_time)
        self._mission_scenario_id = mi_cfg
        self.mission.Commit()

    # ── io-link routing ────────────────────────────────────────────────────────

    @staticmethod
    def _find_child(identifier, attr):
        """Return the child WME of `identifier` with attribute `attr`, or None."""
        if identifier is None:
            return None
        for i in range(identifier.GetNumberChildren()):
            wme = identifier.GetChild(i)
            if wme.GetAttribute() == attr:
                return wme
        return None

    def _on_env_signal(self, id, user_data, agent, wme):
        """Output handler: environment wrote ^signal. Queue it for the next _deliver."""
        self._pending_signal = wme.GetValueAsString()

    def _deliver(self):
        """Move every message AFTER all three agents have run — one cycle per hop."""
        self._deliver_signal()
        self._deliver_attacker()

    def _deliver_signal(self):
        """environment ^signal -> scenario + mission inputs (broadcast).

        One-shot: last cycle's WME is destroyed before a new one is planted, so a
        signal lives exactly one cycle. That is enough — a Soar decision cycle runs
        propose and apply together, so both receivers consume it in the one cycle
        it is visible (scenario picks the attacker, mission initializes)."""
        for agent, h in self._signal_wmes:
            agent.DestroyWME(h)
            agent.Commit()
        self._signal_wmes = []

        if self._pending_signal is not None:
            for agent in (self.scenario, self.mission):
                h = agent.CreateStringWME(agent.GetInputLink(), "signal", self._pending_signal)
                self._signal_wmes.append((agent, h))
                agent.Commit()
            self._pending_signal = None

    def _deliver_attacker(self):
        """scenario output ^scenario.attacker-id -> mission input ^scenario.attacker-id.

        PERSISTENT, unlike the signal: mission re-reads it on every one of the 20
        examines, so it must outlive the cycle it arrived in. Polled rather than
        handled because the SOAR writes it nested under ^scenario; the latch below
        is what keeps the poll from re-delivering."""
        if self._attacker_delivered:
            return
        sc = self._find_child(self.scenario.GetOutputLink(), "scenario")
        if sc is not None and sc.IsIdentifier():
            aid_wme = self._find_child(sc.ConvertToIdentifier(), "attacker-id")
            if aid_wme is not None:
                aid = int(aid_wme.GetValueAsString())
                self._attacker_wme = self.mission.CreateIntWME(
                    self._mission_scenario_id, "attacker-id", aid)
                self.mission.Commit()
                self.attacker_id = aid
                self._attacker_delivered = True

    # ── run loop ─────────────────────────────────────────────────────────────

    def step(self):
        """One decision cycle for all three agents, then delivery. Deliver-AFTER is
        what buys the one-cycle-per-hop cost and makes the kernel's internal agent
        order irrelevant — see the scheduling note at the top of this file."""
        self.kernel.RunAllAgents(5, sml.sml_PHASE)
        self._deliver()

    def mission_state(self):
        """Read mission's current (^action, ^current-person-id, ^wall-clock, ^tagged-attacker)."""
        raw = self.mission.ExecuteCommandLine("print s1")

        def g(attr, cast=str):
            m = re.search(r"\^" + re.escape(attr) + r"\s+(\S+)", raw)
            return cast(m.group(1)) if m else None

        return {
            "action": g("action"),
            "person": g("current-person-id"),
            "wall": g("wall-clock"),
            "tagged": g("tagged-attacker"),
        }

    def run_episode(self, max_steps=300, trace=False):
        """Run until mission reaches a terminal ^action, or max_steps. Returns the ending."""
        for t in range(max_steps):
            self.step()
            st = self.mission_state()
            if trace:
                # str(): ^action does not exist until initialize applies, so the
                # first steps legitimately read None.
                print(f"  step {t:3d}: action={str(st['action']):>9} "
                      f"person={st['person']} wall={st['wall']} tagged={st['tagged']} "
                      f"attacker={self.attacker_id}")
            if st["action"] in _TERMINAL:
                return st["action"]
        return "TIMEOUT"

    # ── lifecycle ────────────────────────────────────────────────────────────

    def reset(self, seed=None):
        """Fresh episode: drop dynamic WMEs, re-init agents (static inputs persist),
        reseed the scenario RNG so softmax picks vary."""
        for agent, h in self._signal_wmes:
            agent.DestroyWME(h)
        self._signal_wmes = []
        if self._attacker_wme is not None:
            self.mission.DestroyWME(self._attacker_wme)
            self._attacker_wme = None
        for agent in (self.environment, self.scenario, self.mission):
            agent.Commit()
            agent.InitSoar()

        if seed is not None:
            self.scenario.ExecuteCommandLine(f"srand {seed}")

        self._pending_signal = None
        self._attacker_delivered = False
        self.attacker_id = None

    def shutdown(self):
        if self.kernel is not None:
            self.kernel.Shutdown()
            self.kernel = None
