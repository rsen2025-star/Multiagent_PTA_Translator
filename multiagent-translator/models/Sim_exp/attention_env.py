"""
Sim_exp — live SML harness for the ICS attention 3-agent SOAR build.

Runs the three agents (environment, scenario, mission) in one Soar kernel and
wires their io-links the way the PRISM model composes its modules:

    environment --[signal transition-mission]--> scenario  AND  mission
    scenario    --[scenario.attacker-id]-------> mission

Nothing domain-specific is hardcoded beyond the experiment knobs
(deadline, use_box, box_accuracy) and the crowd size N=20 (= PRISM's N).

Reference bridge: D:\\Rajarshi\\Courses\\MBSE\\Multi-agent Train_gate\\env\\environment.py
That one used flat string signals in a linear chain; ours adds typed/nested
WMEs and a broadcast+point-to-point topology.
"""

import os, sys, re

# Reuse the Train_gate SOAR SML runtime (no copy).
_SOAR_BIN = r"D:\Rajarshi\Courses\MBSE\Multi-agent Train_gate\SOAR\bin"
os.add_dll_directory(_SOAR_BIN)
sys.path.insert(0, _SOAR_BIN)
import Python_sml_ClientInterface as sml  # noqa: E402

# Our 3-agent SOAR build (each agent loads its own load.soar).
_AGENTS = r"D:\Rajarshi\AssistLab_project\Darpa\SoarToPrismTranslator-pta-translation\models\ICS_SOAR-attention-3agent"

N_PEOPLE = 20                       # crowd size, ids 0..19 (matches PRISM const N)
_TERMINAL = {"Complete", "Deadline", "Not-Tagged"}


class AttentionEnv:
    """One Soar kernel, three agents, hand-wired io-links."""

    def __init__(self, deadline=5, use_box=1, box_accuracy=0.8):
        self.deadline = deadline
        self.use_box = use_box
        self.box_accuracy = box_accuracy

        self.kernel = None
        self.environment = self.scenario = self.mission = None

        # dynamic (cross-agent) WMEs we place, so reset() can remove them
        self._signal_wmes = []          # ^signal on scenario + mission input-links
        self._attacker_wme = None       # ^attacker-id on mission's ^scenario id
        self._mission_scenario_id = None  # mission input ^scenario identifier

        self._signal_sent = False
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

        # mission: scenario.{use-box(int), deadline(int)}; attacker-id added on delivery
        mi_il = self.mission.GetInputLink()
        mi_cfg = self.mission.CreateIdWME(mi_il, "scenario")
        self.mission.CreateIntWME(mi_cfg, "use-box", self.use_box)
        self.mission.CreateIntWME(mi_cfg, "deadline", self.deadline)
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

    def _deliver_signal(self):
        """environment output ^signal transition-mission -> scenario + mission inputs."""
        if self._signal_sent:
            return
        wme = self._find_child(self.environment.GetOutputLink(), "signal")
        if wme is not None and wme.GetValueAsString() == "transition-mission":
            for agent in (self.scenario, self.mission):
                h = agent.CreateStringWME(agent.GetInputLink(), "signal", "transition-mission")
                self._signal_wmes.append((agent, h))
                agent.Commit()
            self._signal_sent = True

    def _deliver_attacker(self):
        """scenario output ^scenario.attacker-id -> mission input ^scenario.attacker-id."""
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
        """One decision cycle per agent, env->scenario->mission, delivering between."""
        self.environment.RunSelf(5, sml.sml_PHASE)
        self._deliver_signal()
        self.scenario.RunSelf(5, sml.sml_PHASE)
        self._deliver_attacker()
        self.mission.RunSelf(5, sml.sml_PHASE)

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
                print(f"  step {t:3d}: action={st['action']:>9} "
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

        self._signal_sent = False
        self._attacker_delivered = False
        self.attacker_id = None

    def shutdown(self):
        if self.kernel is not None:
            self.kernel.Shutdown()
            self.kernel = None
