# Lessons

Things that were found the hard way here. They are kept because each one cost real measurement to
establish, and because the obvious next move is, in several cases, the wrong one.

Read this before you start optimising or before you delete something that looks unused.

| | |
|---|---|
| [`decoder-performance.md`](decoder-performance.md) | What is and is not the bottleneck, and eight hypotheses that were measured and disproved |
| [`bugs-worth-remembering.md`](bugs-worth-remembering.md) | Four defects whose shape is worth recognising again |
| [`measuring.md`](measuring.md) | How to measure here without fooling yourself |

Two habits run through all of them:

**A thing that is slow is not automatically the thing to fix.** Decoding was the bottleneck once;
it stopped being one and the notes did not. Check before you act on a claim in any document,
including these.

**A failure that produces no error is the expensive kind.** Every defect below compiled, ran, and
looked fine. None of them would have been found by reading the code harder.
