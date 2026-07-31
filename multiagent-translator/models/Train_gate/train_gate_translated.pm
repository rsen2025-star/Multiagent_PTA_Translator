pta

module train

    state_position : [0..2] init 0;
    state_train_count : clock;

    invariant
        (state_position=0 => state_train_count<=6) &
        (state_position=1 => state_train_count<=6) &
        (state_position=2 => state_train_count<=6)
    endinvariant

    [state_io_signal_approach] state_position=0 & state_train_count >= 6 -> (state_position'=1) & (state_train_count'=0);
    [enter] state_position=1 & state_train_count >= 6 -> (state_position'=2) & (state_train_count'=0);
    [state_io_signal_exit] state_position=2 & state_train_count >= 6 -> (state_position'=0) & (state_train_count'=0);

endmodule

module controller

    state_ctrl_state : [0..3] init 0;

    [state_io_signal_approach] state_ctrl_state=0 -> (state_ctrl_state'=1);
    [state_io_command_lower] state_ctrl_state=1 -> (state_ctrl_state'=2);
    [state_io_signal_exit] state_ctrl_state=2 -> (state_ctrl_state'=3);
    [state_io_command_raise] state_ctrl_state=3 -> (state_ctrl_state'=0);

endmodule

module gate

    state_gate_position : [0..3] init 0;
    state_gate_count : clock;

    invariant
        (state_gate_position=1 => state_gate_count<=2) &
        (state_gate_position=3 => state_gate_count<=2)
    endinvariant

    [state_io_command_lower] state_gate_position=0 -> (state_gate_position'=1) & (state_gate_count'=0);
    [lowered] state_gate_position=1 & state_gate_count >= 2 -> (state_gate_position'=2) & (state_gate_count'=0);
    [state_io_command_raise] state_gate_position=2 -> (state_gate_position'=3) & (state_gate_count'=0);
    [raised] state_gate_position=3 & state_gate_count >= 2 -> (state_gate_position'=0) & (state_gate_count'=0);

endmodule

