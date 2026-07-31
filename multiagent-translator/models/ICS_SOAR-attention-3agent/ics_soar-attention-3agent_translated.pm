pta

const int sys_start_mission;
const int use_box;
const double box_accuracy;
const int deadline;

module environment

    state_start_mission : [0..1] init 0;
    state_enviroSet : [0..1] init 0;
    state_mission_started : [0..1] init 0;

    [start_mission] state_start_mission=0 & sys_start_mission > 0 -> (state_start_mission'=1);
    [sync_environment] state_enviroSet=0 -> (state_enviroSet'=1);
    [state_io_signal_transition_mission] state_enviroSet=1 & state_mission_started=0 & state_start_mission=1 -> (state_mission_started'=1);

endmodule

module scenario

    state_io_scenario_attacker_id : [0..19] init 0;

    [state_io_signal_transition_mission] use_box = 1 ->
          box_accuracy : (state_io_scenario_attacker_id'=0)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=1)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=2)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=3)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=4)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=5)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=6)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=7)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=8)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=9)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=10)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=11)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=12)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=13)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=14)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=15)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=16)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=17)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=18)
        + (1.0 - box_accuracy) / 19 : (state_io_scenario_attacker_id'=19);
    [state_io_signal_transition_mission] use_box = 0 ->
          1.0 / 20 : (state_io_scenario_attacker_id'=0)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=1)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=2)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=3)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=4)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=5)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=6)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=7)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=8)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=9)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=10)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=11)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=12)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=13)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=14)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=15)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=16)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=17)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=18)
        + 1.0 / 20 : (state_io_scenario_attacker_id'=19);

endmodule

module mission

    state_name : [0..1] init 0;
    state_current_person_boxed : [0..1] init 0;
    state_tagged_attacker : [0..1] init 0;
    state_action : [0..5] init 0;
    state_current_person_id : [0..20] init 0;
    state_wall_clock : clock;
    t1 : clock;

    invariant
        (state_name=0 => t1<=1) &
        (state_current_person_boxed=1 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 => state_wall_clock<=deadline) &
        (state_current_person_boxed=1 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 => t1<=1) &
        (state_current_person_boxed=0 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 => state_wall_clock<=deadline) &
        (state_current_person_boxed=0 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 => t1<=1) &
        (state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id >= 20 => t1<=1)
    endinvariant

    [state_io_signal_transition_mission] state_name=0 -> (state_name'=1) & (state_action'=1) & (state_current_person_id'=0) & (state_current_person_boxed'=use_box) & (state_tagged_attacker'=0) & (state_wall_clock'=0) & (t1'=0);
    [examine_box_hit] state_current_person_boxed=1 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 & state_wall_clock <= deadline & state_io_scenario_attacker_id = state_current_person_id & t1 >= 1 -> (state_tagged_attacker'=1) & (state_action'=2) & (state_current_person_id'=state_current_person_id + 1) & (state_current_person_boxed'=0) & (t1'=0);
    [examine_box_miss] state_current_person_boxed=1 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 & state_wall_clock <= deadline & state_io_scenario_attacker_id != state_current_person_id & t1 >= 1 -> (state_current_person_id'=state_current_person_id + 1) & (state_current_person_boxed'=0) & (t1'=0);
    [examine_hit] state_current_person_boxed=0 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 & state_wall_clock <= deadline & state_io_scenario_attacker_id = state_current_person_id & t1 >= 1 -> (state_tagged_attacker'=1) & (state_action'=2) & (state_current_person_id'=state_current_person_id + 1) & (t1'=0);
    [examine_miss] state_current_person_boxed=0 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 & state_wall_clock <= deadline & state_io_scenario_attacker_id != state_current_person_id & t1 >= 1 -> (state_current_person_id'=state_current_person_id + 1) & (t1'=0);
    [deadline_reached] state_tagged_attacker=0 & state_name=1 & state_wall_clock >= deadline -> (state_action'=3);
    [mission_complete_untagged] state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id >= 20 -> (state_action'=4);
    [mission_complete_tagged] state_tagged_attacker=1 & state_name=1 -> (state_action'=5);

endmodule

