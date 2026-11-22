# Superseded prompt and schema versions

Owner: `rag-engineer`. These files are kept unchanged as history. No code reads them; each
version is superseded by the file named here, which is the one the code pins.

| Superseded file | Version | Superseded by | Since |
|---|---|---|---|
| `prompts/runtime/answer-synthesis.v1.txt` | `scholarchain.rag.prompt.answer-synthesis.v1` | `prompts/runtime/answer-synthesis.v3.txt` (via v2) | RE-P9-04 (v2), RE-P9-05 (v3) |
| `prompts/runtime/answer-synthesis.v2.txt` | `scholarchain.rag.prompt.answer-synthesis.v2` | `prompts/runtime/answer-synthesis.v3.txt` | RE-P9-05 (CR-P9-02 F1) |
| `prompts/schemas/answer-synthesis-output.v1.schema.json` | `scholarchain.rag.answer-synthesis-output.v1` | `prompts/schemas/answer-synthesis-output.v3.schema.json` (via v2) | RE-P9-04 (v2), RE-P9-05 (v3) |
| `prompts/schemas/answer-synthesis-output.v2.schema.json` | `scholarchain.rag.answer-synthesis-output.v2` | `prompts/schemas/answer-synthesis-output.v3.schema.json` | RE-P9-05 (CR-P9-02 F1) |
| `prompts/schemas/audit-record.v1.schema.json` | `scholarchain.rag.audit-record.v1` | `prompts/schemas/audit-record.v3.schema.json` (via v2) | RE-P9-04 (v2), RG-P11-02 (v3) |
| `prompts/schemas/audit-record.v2.schema.json` | `scholarchain.rag.audit-record.v2` | `prompts/schemas/audit-record.v3.schema.json` | RG-P11-02 (adds `verifierVersion`) |

Why v3 supersedes v2 (RE-P9-05): v2 verified the model's claim text against a closed wording
(`scholarchain.rag.claim-wording.v1`). v3 removes that licence. The answer text of every kept
claim is rendered from its verified assertions (`scholarchain.rag.claim-rendering.v1`, verifier
`scholarchain.rag.claim-verification.v3`). The model's claim text is a draft that is never shown.

Verifier v4 (RG-P11-02, CR-P11-01-1 and -2): `scholarchain.rag.claim-verification.v4` shows a
subject in a problem label only when it is the subject of a bundle fact (else the fixed
placeholder `(a subject not in the evidence)`), and fabricated evidence IDs only as a count. The
prompt (`answer-synthesis.v3`) and the output schema (`answer-synthesis-output.v3`) are unchanged.
The audit record v3 records the verifier version (`verifierVersion`).
