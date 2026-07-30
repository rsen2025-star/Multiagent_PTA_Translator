// Train_gate — DISCRETE version (digital clocks); MDP, integer time
mdp

module Train
    s_train   : [0..2] init 0;   // 0=far 1=near 2=in
    clk_train : [0..8] init 0;   // cap kx+1 = 8  (reaches 7)

    [tick]      clk_train<7 -> (clk_train'=clk_train+1);   // blocked at 7 => must act
    [approach]  s_train=0 & clk_train>=7 -> (s_train'=1) & (clk_train'=0);
    [enter]     s_train=1 & clk_train>=7 -> (s_train'=2) & (clk_train'=0);
    [exit]      s_train=2 & clk_train>=7 -> (s_train'=0) & (clk_train'=0);
endmodule

module Controller
    s_ctrl   : [0..3] init 0;    // 0=idle 1=appr_recv 2=lower_sent 3=exit_recv
    clk_ctrl : [0..2] init 0;    // cap kx+1 = 2  (reaches 1)

    [tick]        (s_ctrl=0 | s_ctrl=2 | clk_ctrl<1) -> (clk_ctrl'=min(clk_ctrl+1,2));
    [approach_c]  s_ctrl=0 -> (s_ctrl'=1) & (clk_ctrl'=0);
    [lower_c]     s_ctrl=1 & clk_ctrl>=1 -> (s_ctrl'=2);
    [exit_c]      s_ctrl=2 -> (s_ctrl'=3) & (clk_ctrl'=0);
    [raise_c]     s_ctrl=3 & clk_ctrl>=1 -> (s_ctrl'=0);
endmodule

module Gate
    s_gate   : [0..3] init 0;    // 0=up 1=lowering 2=down 3=raising
    clk_gate : [0..4] init 0;    // cap kx+1 = 4  (reaches 3)

    [tick]      (s_gate=0 | s_gate=2 | clk_gate<3) -> (clk_gate'=min(clk_gate+1,4));
    [lower_g]   s_gate=0 -> (s_gate'=1) & (clk_gate'=0);
    [lowered]   s_gate=1 & clk_gate>=3 -> (s_gate'=2);
    [raise_g]   s_gate=2 -> (s_gate'=3) & (clk_gate'=0);
    [raised]    s_gate=3 & clk_gate>=3 -> (s_gate'=0);
endmodule

module Comms
    s_comms   : [0..4] init 0;   // 0=idle 1..4 = carrying a signal
    clk_comms : [0..2] init 0;   // cap kx+1 = 2  (reaches 1)

    [tick]        (s_comms=0 | clk_comms<1) -> (clk_comms'=min(clk_comms+1,2));
    [approach]    s_comms=0 -> (s_comms'=1) & (clk_comms'=0);
    [approach_c]  s_comms=1 & clk_comms>=1 -> (s_comms'=0);
    [lower_c]     s_comms=0 -> (s_comms'=2) & (clk_comms'=0);
    [lower_g]     s_comms=2 & clk_comms>=1 -> (s_comms'=0);
    [exit]        s_comms=0 -> (s_comms'=3) & (clk_comms'=0);
    [exit_c]      s_comms=3 & clk_comms>=1 -> (s_comms'=0);
    [raise_c]     s_comms=0 -> (s_comms'=4) & (clk_comms'=0);
    [raise_g]     s_comms=4 & clk_comms>=1 -> (s_comms'=0);
endmodule
