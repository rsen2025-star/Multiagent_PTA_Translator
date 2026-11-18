# Railroad simulation

Runs the Railroad agents in a live SOAR kernel over a grid of travel and mechanical delays
and marks each setting safe or unsafe.
Needs Python 3.12 and a SOAR install (set `SOAR_BIN` to its `bin` folder).

```
python run_sweep.py --travel 6:1:20 --mechanical 2:1:12
python detect.py --plot
```
