"""
Sim_train_gate — live SML harness for the parameterised Train_gate SOAR build.

Runs train / controller / gate in one Soar kernel and wires their io-links the way
the PRISM model composes its modules:

    train      --[^signal  approach | exit]--> controller
    controller --[^command lower    | raise]--> gate

THE POINT OF THIS HARNESS
-------------------------
Delivery happens AFTER RunAllAgents, so a message written during decision cycle N
is only visible to its receiver in cycle N+1. That one-cycle-per-hop cost is the
`r` that the translated PRISM model currently assumes is ZERO — there the sender's
and receiver's labels are fused into a single synchronised transition, which makes
the handover instantaneous. Measuring `r` is what this harness is for.

    PRISM (today)  safe <=> mechanical_delay <= travel_delay
    SOAR sim       safe <=> mechanical_delay <= travel_delay - r

The two durations are injected on the input-links, exactly as PRISM's -const does:

    train  ^io.input-link.travel-delay
    gate   ^io.input-link.mechanical-delay

Nothing about the SOAR is modified — Train_gate_param already reads both durations
from its input-link and already writes both messages to its output-link.

Reference bridge: D:\\Rajarshi\\Courses\\MBSE\\Multi-agent Train_gate\\env\\environment.py
That one hardcoded the durations inside the agents; ours injects them so the same
agents can be swept.

ONE CYCLE PER CONFIGURATION
---------------------------
`train*clear*output` is commented out in the SOAR, so an output WME persists once
written and re-writing the same value is a no-op — the output handler would not
fire a second time. That is harmless because the safety question is decided in the
FIRST crossing: run one cycle, reset, move to the next configuration.
"""

import os
import sys
import re

# Reuse the Train_gate SOAR SML runtime (no copy), same as models/Sim_exp.
_SOAR_BIN = r"D:\Rajarshi\Courses\MBSE\Multi-agent Train_gate\SOAR\bin"
os.add_dll_directory(_SOAR_BIN)
sys.path.insert(0, _SOAR_BIN)
import Python_sml_ClientInterface as sml  # noqa: E402

# Resolved relative to this file so a copied-out tree tests ITS OWN agents.
_AGENTS = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       os.pardir, "Train_gate_param")

CROSSING = "in"       # train position that is dangerous
GATE_DOWN = "down"    # the only gate position that makes it safe


def _f(value):
    """Render a WME for the log. None is not missing data — it means the WME does
    not exist yet, which is real: the gate has no ^gate-count until it first moves."""
    return "-" if value is None else str(value)


class TrainGateEnv:
    """One Soar kernel, three agents, Python as the wire between them."""

    def __init__(self, travel_delay=6, mechanical_delay=2):
        self.travel_delay = travel_delay
        self.mechanical_delay = mechanical_delay

        self.kernel = None
        self.train = self.controller = self.gate = None

        # messages captured this cycle, not yet handed to the receiver
        self._pending_signal = None
        self._pending_command = None
        # the input WMEs we placed, so the next delivery can retract them
        self._signal_wme = None
        self._command_wme = None
        # the two duration WMEs, kept so a sweep can re-point them
        self._travel_wme = None
        self._mech_wme = None

        self.tick_count = 0
        self.deliveries = []          # (tick, sender, receiver, attr, value)

    # ── output handlers ──────────────────────────────────────────────────────

    def _on_train_signal(self, id, user_data, agent, wme):
        self._pending_signal = wme.GetValueAsString()

    def _on_ctrl_command(self, id, user_data, agent, wme):
        self._pending_command = wme.GetValueAsString()

    # ── setup ────────────────────────────────────────────────────────────────

    def create_agents(self):
        self.kernel = sml.Kernel.CreateKernelInNewThread()
        if self.kernel.HadError():
            raise RuntimeError(self.kernel.GetLastErrorDescription())

        self.train = self.kernel.CreateAgent("train")
        self.controller = self.kernel.CreateAgent("controller")
        self.gate = self.kernel.CreateAgent("gate")

        for agent, folder in ((self.train, "train"),
                              (self.controller, "controller"),
                              (self.gate, "gate")):
            # settings.soar is not in the load chain (a top-level source line would
            # collapse the agents into one module for the translator), so the one
            # setting it carries is issued here instead. Without it an agent with
            # nothing to propose subgoals on a state-no-change instead of waiting.
            agent.ExecuteCommandLine("waitsnc --on")
            path = os.path.join(_AGENTS, folder, "load.soar")
            agent.LoadProductions(path)
            if agent.HadError():
                raise RuntimeError(f"{folder} load error: {agent.GetLastErrorDescription()}")

        self.train.AddOutputHandler("signal", self._on_train_signal, None)
        self.controller.AddOutputHandler("command", self._on_ctrl_command, None)

        self._build_static_inputs()

    def _build_static_inputs(self):
        """The two durations — this harness's equivalent of PRISM's -const.

        Client-owned input WMEs survive InitSoar, so they are built once."""
        self._travel_wme = self.train.CreateIntWME(
            self.train.GetInputLink(), "travel-delay", self.travel_delay)
        self.train.Commit()

        self._mech_wme = self.gate.CreateIntWME(
            self.gate.GetInputLink(), "mechanical-delay", self.mechanical_delay)
        self.gate.Commit()

    # ── the wire ─────────────────────────────────────────────────────────────

    def tick(self):
        """One decision cycle for every agent, then hand messages across."""
        self.tick_count += 1
        self.kernel.RunAllAgents(5, sml.sml_PHASE)
        self._deliver()

    def _deliver(self):
        """Move output-link messages onto the receivers' input-links.

        Running AFTER the agents is what costs one decision cycle per hop. A
        delivered message lives for exactly one cycle — the receiver's propose and
        apply both run inside a single decision, so one is enough."""
        if self._signal_wme is not None:
            self.controller.DestroyWME(self._signal_wme)
            self._signal_wme = None
        if self._command_wme is not None:
            self.gate.DestroyWME(self._command_wme)
            self._command_wme = None

        if self._pending_signal:
            self._signal_wme = self.controller.CreateStringWME(
                self.controller.GetInputLink(), "signal", self._pending_signal)
            self.controller.Commit()
            self.deliveries.append(
                (self.tick_count, "train", "controller", "signal", self._pending_signal))
            self._pending_signal = None

        if self._pending_command:
            self._command_wme = self.gate.CreateStringWME(
                self.gate.GetInputLink(), "command", self._pending_command)
            self.gate.Commit()
            self.deliveries.append(
                (self.tick_count, "controller", "gate", "command", self._pending_command))
            self._pending_command = None

    # ── observation ──────────────────────────────────────────────────────────

    @staticmethod
    def _attr(agent, attribute):
        raw = agent.ExecuteCommandLine("print s1")
        m = re.search(r"\^" + re.escape(attribute) + r"\s+(\S+)", raw)
        return m.group(1) if m else None

    def states(self):
        return {
            "train": self._attr(self.train, "position"),
            "train_count": self._attr(self.train, "train-count"),
            "ctrl": self._attr(self.controller, "ctrl-state"),
            "gate": self._attr(self.gate, "gate-position"),
            "gate_count": self._attr(self.gate, "gate-count"),
        }

    @staticmethod
    def unsafe(s):
        """The PRISM property, verbatim: state_position=2 & state_gate_position!=2."""
        return s["train"] == CROSSING and s["gate"] != GATE_DOWN

    # ── run loop ─────────────────────────────────────────────────────────────

    def run_cycle(self, max_ticks=400, sink=None):
        """Run one full train circuit (far -> near -> in -> far).

        `sink` is a callable taking one line of text without a newline — pass
        `print` for a live trace, or a file's writer to RECORD the run. The lines
        it emits are purely factual (positions, counts, deliveries); no verdict is
        written, so a recorded log can be re-analysed by detect.py without having
        to re-run Soar.

        Returns the outcome of THIS configuration:
            unsafe            train was in the crossing with the gate not down
            first_unsafe      tick that first happened, or None
            reached_crossing  the train got to `in` at all
            completed         the train made it back to `far`
            ticks             decision cycles consumed
            handover          decision cycles from the train signalling `approach`
                              to the gate actually starting to lower — this is `r`
        """
        unsafe = False
        first_unsafe = None
        reached_crossing = False
        completed = False
        lowering_tick = None
        logged_deliveries = 0

        for _ in range(max_ticks):
            self.tick()
            s = self.states()

            if lowering_tick is None and s["gate"] == "lowering":
                lowering_tick = self.tick_count

            if sink is not None:
                while logged_deliveries < len(self.deliveries):
                    t, src, dst, attr, val = self.deliveries[logged_deliveries]
                    sink(f"  >>> DELIVER tick {t:3d}  {src} --[{attr}={val}]--> {dst}")
                    logged_deliveries += 1
                sink(f"  tick {self.tick_count:3d}  "
                     f"train={_f(s['train']):<5} count={_f(s['train_count']):>3}  "
                     f"ctrl={_f(s['ctrl']):<2}  "
                     f"gate={_f(s['gate']):<8} count={_f(s['gate_count']):>3}")

            if s["train"] == CROSSING:
                reached_crossing = True
                if not unsafe and self.unsafe(s):
                    unsafe = True
                    first_unsafe = self.tick_count
            elif reached_crossing and s["train"] == "far":
                completed = True
                break

        return {
            "travel_delay": self.travel_delay,
            "mechanical_delay": self.mechanical_delay,
            "unsafe": unsafe,
            "first_unsafe": first_unsafe,
            "reached_crossing": reached_crossing,
            "completed": completed,
            "ticks": self.tick_count,
            "handover": self.handover(lowering_tick),
        }

    def handover(self, lowering_tick):
        """`r` — decision cycles from the train signalling `approach` to the gate
        beginning to lower. Three things happen in that window and each costs one
        cycle: the wire to the controller, the controller's own decision, and the
        wire to the gate. It is the whole quantity the fused PRISM labels drop."""
        approach = next((t for t, _s, _d, a, v in self.deliveries
                         if a == "signal" and v == "approach"), None)
        if approach is None or lowering_tick is None:
            return None
        return lowering_tick - approach

    # ── lifecycle ────────────────────────────────────────────────────────────

    def reset(self, travel_delay=None, mechanical_delay=None):
        """Fresh circuit, optionally at new durations. Static inputs are re-pointed
        rather than rebuilt so one kernel can serve a whole sweep."""
        if self._signal_wme is not None:
            self.controller.DestroyWME(self._signal_wme)
            self._signal_wme = None
        if self._command_wme is not None:
            self.gate.DestroyWME(self._command_wme)
            self._command_wme = None
        self._pending_signal = None
        self._pending_command = None
        self.deliveries = []
        self.tick_count = 0

        if travel_delay is not None and travel_delay != self.travel_delay:
            self.train.DestroyWME(self._travel_wme)
            self.travel_delay = travel_delay
            self._travel_wme = self.train.CreateIntWME(
                self.train.GetInputLink(), "travel-delay", travel_delay)
        if mechanical_delay is not None and mechanical_delay != self.mechanical_delay:
            self.gate.DestroyWME(self._mech_wme)
            self.mechanical_delay = mechanical_delay
            self._mech_wme = self.gate.CreateIntWME(
                self.gate.GetInputLink(), "mechanical-delay", mechanical_delay)

        for agent in (self.train, self.controller, self.gate):
            agent.Commit()
            agent.InitSoar()

    def shutdown(self):
        if self.kernel is not None:
            self.kernel.Shutdown()
            self.kernel = None
