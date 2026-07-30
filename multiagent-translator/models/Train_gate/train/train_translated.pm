pta

module train

    state_position : [0..2] init 0;
    state_train_count : clock;

    invariant
        (state_position=0 => state_train_count<=7) &
        (state_position=1 => state_train_count<=7) &
        (state_position=2 => state_train_count<=7)
    endinvariant

    [state_io_signal_approach] state_position=0 & state_train_count >= 7 -> (state_position'=1) & (state_train_count'=0);
    [enter] state_position=1 & state_train_count >= 7 -> (state_position'=2) & (state_train_count'=0);
    [state_io_signal_exit] state_position=2 & state_train_count >= 7 -> (state_position'=0) & (state_train_count'=0);

endmodule

