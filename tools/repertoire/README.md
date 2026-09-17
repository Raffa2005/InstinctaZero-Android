# Portable repertoire builder

`repertoire_index.py`, its JSON schema, pinned dependency and three policy test
files were copied unchanged from the installed source builder on 17 September
2026. They contain code and artificial tests, not the private repertoire corpus.
Source schema version is 1 and resolver policy is 4.

Start with [the GitHub-only handoff](../../docs/repertoire-optimization-handoff.md).
`generate_fixture.py` produces fresh synthetic PGNs, sidecars and a database in a
new directory. It never imports a real repertoire. Duplicate copies are deliberate
performance fixtures, not additional chess knowledge.

This source package lets a remote worker reproduce the downloaded format and test
Android changes, including an on-device derived index. Editing it does not change
the installed PC builder automatically. Any eventual production builder changes
must be reconciled and validated locally before deployment.
