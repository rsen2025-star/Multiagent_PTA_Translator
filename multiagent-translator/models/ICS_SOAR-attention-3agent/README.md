# Attention case study

Three SOAR agents: `environment/` starts the mission, `scenario/` picks the hidden
attacker, `mission/` inspects people until it tags the attacker or reaches the deadline.

Check the translated model in PRISM:

```
prism ics_soar-attention-3agent_translated.pm ics_translated.props -const deadline=20,box_accuracy=0.75,use_box=1,inspection_time=1,sys_start_mission=1 -ptamethod digital
```
