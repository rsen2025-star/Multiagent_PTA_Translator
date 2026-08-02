pta

const int sys_start_mission;
const int scenario_use_box;
const int scenario_deadline;
const int scenario_attacker_id;

module environment

    state_start_mission : [0..1] init 0;
    state_enviroSet : [0..1] init 0;
    state_handoff_sent : [0..1] init 0;

    [start_mission] state_start_mission=0 & sys_start_mission > 0 -> (state_start_mission'=1);
    [sync_environment] state_enviroSet=0 -> (state_enviroSet'=1);
    [state_io_signal_transition_mission] state_enviroSet=1 & state_handoff_sent=0 & state_start_mission=1 -> (state_handoff_sent'=1);

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
        (state_current_person_boxed=1 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 => state_wall_clock<=scenario_deadline) &
        (state_current_person_boxed=1 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 => t1<=1) &
        (state_current_person_boxed=0 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 => state_wall_clock<=scenario_deadline) &
        (state_current_person_boxed=0 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 => t1<=1) &
        (state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id >= 20 => t1<=1)
    endinvariant

    [state_io_signal_transition_mission] state_name=0 -> (state_name'=1) & (state_action'=1) & (state_current_person_id'=0) & (state_current_person_boxed'=scenario_use_box) & (state_tagged_attacker'=0) & (state_wall_clock'=0) & (t1'=0);
    [examine_box_hit] state_current_person_boxed=1 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 & state_wall_clock <= scenario_deadline & scenario_attacker_id = state_current_person_id & t1 >= 1 -> (state_tagged_attacker'=1) & (state_action'=2) & (state_current_person_id'=state_current_person_id + 1) & (state_current_person_boxed'=0) & (t1'=0);
    [examine_box_miss] state_current_person_boxed=1 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 & state_wall_clock <= scenario_deadline & scenario_attacker_id != state_current_person_id & t1 >= 1 -> (state_current_person_id'=state_current_person_id + 1) & (state_current_person_boxed'=0) & (t1'=0);
    [examine_hit] state_current_person_boxed=0 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 & state_wall_clock <= scenario_deadline & scenario_attacker_id = state_current_person_id & t1 >= 1 -> (state_tagged_attacker'=1) & (state_action'=2) & (state_current_person_id'=state_current_person_id + 1) & (t1'=0);
    [examine_miss] state_current_person_boxed=0 & state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id < 20 & state_wall_clock <= scenario_deadline & scenario_attacker_id != state_current_person_id & t1 >= 1 -> (state_current_person_id'=state_current_person_id + 1) & (t1'=0);
    [deadline_reached] state_tagged_attacker=0 & state_name=1 & state_wall_clock >= scenario_deadline -> (state_action'=3);
    [mission_complete_untagged] state_tagged_attacker=0 & state_action=1 & state_name=1 & state_current_person_id >= 20 -> (state_action'=4);
    [mission_complete_tagged] state_tagged_attacker=1 & state_name=1 -> (state_action'=5);

endmodule

