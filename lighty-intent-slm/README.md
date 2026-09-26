# lighty-intent-slm

The NL-to-intent translation microservice ("SLM sidecar") that lighty's
`lighty-intent-nl-client` (Java) module calls over HTTP. Its only job is to
turn a natural-language operator request into a **draft** structured intent,
or a list of clarifying questions. It never produces device-level
configuration.

This is a Python sub-tree inside the lighty (Java/Maven) repository. It is
**not** part of the Maven build and has its own, independent Python
toolchain (see below).

## Endpoint contract

### `POST /v1/translate`

Request body:

```json
{
  "text": "Guarantee 200 Mbps and under 15 ms latency between siteA and siteB, isolated from other traffic.",
  "context_hint": null
}
```

- `text` (string, required): verbatim operator natural-language request.
- `context_hint` (string or null, optional): a freeform hint (e.g. a domain
  or site name) to help disambiguate the request.

Response body:

```json
{
  "status": "INTENT_DRAFTED",
  "draft_intent_json": "{\"lighty-intent:intent\": [ ... ]}",
  "clarification_questions": [],
  "confidence": 0.85
}
```

- `status`: one of `INTENT_DRAFTED`, `CLARIFICATION_NEEDED`, `ERROR`.
- `draft_intent_json`: a **string** containing RFC 7951 JSON, present only
  when `status` is `INTENT_DRAFTED`. Once parsed, it is a `/intents/intent`
  entry shaped per `lighty-intent.yang` / `lighty-intent-transport.yang` /
  `lighty-intent-ran-cco.yang` (see
  `lighty-models/lighty-intent-models/src/main/yang/`), e.g.:

  ```json
  {
    "lighty-intent:intent": [
      {
        "intent-id": "intent-...",
        "intent-name": "Guarantee 200 Mbps and under 15 ms latency ...",
        "domain": "lighty-intent-transport:transport",
        "source": "NL_SLM",
        "original-nl-text": "Guarantee 200 Mbps and under 15 ms latency between siteA and siteB, isolated from other traffic.",
        "expectation": [
          {
            "expectation-id": "exp-...",
            "expectation-verb": "ENSURE",
            "expectation-target": [
              {
                "expectation-object-type": "lighty-intent-transport:min-bandwidth",
                "expectation-object-instance": "link-siteA-siteB",
                "target-condition": "IS_GREATER_THAN",
                "target-value-range": ["200"],
                "unit": "Mbps"
              },
              {
                "expectation-object-type": "lighty-intent-transport:max-latency",
                "expectation-object-instance": "link-siteA-siteB",
                "target-condition": "IS_LESS_THAN",
                "target-value-range": ["15"],
                "unit": "ms"
              },
              {
                "expectation-object-type": "lighty-intent-transport:traffic-isolation",
                "expectation-object-instance": "link-siteA-siteB",
                "target-condition": "IS_EQUAL_TO",
                "target-value-range": ["true"],
                "unit": "boolean"
              }
            ]
          }
        ]
      }
    ]
  }
  ```

  The full JSON Schema for this shape lives at
  `schema/intent.schema.json` (also loadable from Python via
  `app.schema.load_intent_schema()`), and is what `tests/test_translate.py`
  validates every drafted intent against.

- `clarification_questions`: non-empty only when `status` is
  `CLARIFICATION_NEEDED`.
- `confidence`: `0.0`-`1.0`.

The caller (`lighty-intent-nl-client`) reviews `draft_intent_json` (and lets
a human edit it) before ever POSTing it to lighty-intent-core's
`/intents/intent`.

## Supported use cases (v1 MVP)

1. **Transport QoS / isolation**: "guarantee N Mbps and under M ms latency
   between A and B, isolated from other traffic". Produces a
   `lighty-intent-transport:transport` domain intent with
   `min-bandwidth` / `max-latency` / `max-packet-loss` / `traffic-isolation`
   expectation targets.
2. **RAN coverage / capacity**: "keep PRB utilization under X% in cell/site
   Y". Produces a `lighty-intent-ran-cco:ran` domain intent with
   `prb-utilization-max` / `cell-coverage-target` /
   `cell-edge-throughput-min` expectation targets.

Anything else is reported as `CLARIFICATION_NEEDED` with follow-up
questions, never guessed at.

## v1 implementation note: deterministic rule-based extractor

`app/extractor.py` is a **deterministic, rule-based (regex/keyword)**
extractor. This stands in for a real small language model, on purpose: it is
fully inspectable, has no external dependencies, and never guesses beyond
what its rules cover.

The seam for the real model is `app.extractor.translate_with_model(text,
context)`. It returns `None` today (falling through to the rule-based
extractor); its docstring spells out exactly what a real implementation
would do: load a small open-weight model (e.g. Qwen2.5-3B-Instruct or
Llama-3.2-3B-Instruct) served locally via llama.cpp or vLLM, build a
JSON-Schema-constrained decoding grammar from `schema/intent.schema.json` so
the model cannot structurally emit anything outside the draft-intent shape,
few-shot prompt it, and ground it with retrieval over the real inventory
(see below).

`app/inventory.py`'s `fetch_inventory_context(hint)` is a tiny inventory
"RAG" stub: it returns a small static example inventory (a few site/link ids
for transport, a few cell ids for RAN). A real implementation would instead
call lighty's own RESTCONF northbound (e.g. `GET /rests/data/...`) to fetch
the site/link/cell identifiers that actually exist right now, both to
ground extracted instance names against reality and to retrieve relevant
context for the model prompt. The rule-based extractor here only consults it
to pick a *plausible* instance id when the operator's text doesn't name one
explicitly -- it never overrides an id the text did name.

## Safety model

- This service **only drafts intents**. It never talks to the managed
  network, and it makes no outbound network calls of any kind (no NETCONF,
  gNMI, A1, RESTCONF-to-devices, and no call to any external LLM API).
- It never produces device-level configuration. The output is always a
  draft `/intents/intent` entry (3GPP TS 28.312-shaped), not compiled config.
- Turning a drafted intent into actual device configuration only happens
  later, in the Java `lighty-intent-core` pipeline, after: schema/semantic
  validation, conflict detection (`dry-run`), and **explicit human
  approval** (`approve-intent`). This sidecar has no path to skip any of
  that.

## Running locally

```bash
cd lighty-intent-slm
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

The service listens on `http://127.0.0.1:8000` by default;
`POST http://127.0.0.1:8000/v1/translate` is the endpoint above, and
`GET /health` is a liveness probe.

## Running tests

```bash
cd lighty-intent-slm
pip install -r requirements.txt
pytest
```

## Docker

```bash
cd lighty-intent-slm
docker build -t lighty-intent-slm .
docker run --rm -p 8000:8000 lighty-intent-slm
```
