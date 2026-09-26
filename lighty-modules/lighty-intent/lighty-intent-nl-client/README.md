# lighty-intent-nl-client

Implementations of `lighty-intent-api`'s `NlTranslator` SPI: the backend of the intent
module's `translate-nl` RPC (see `lighty-intent.yang`).

## The translate-nl pipeline

`translate-nl` never writes to the intent store and never actuates anything: it only turns an
operator's natural-language request into a *draft* `/intents/intent` entry (or a set of
clarifying questions) that a caller still has to review and submit separately.

This module provides three `NlTranslator` implementations, meant to be composed as:

```
FallbackNlTranslator
├── primary:   SidecarNlTranslator   (external Python NL-to-intent SLM sidecar, over HTTP)
└── secondary: RuleBasedNlTranslator (deterministic, offline, regex/keyword-based)
```

`FallbackNlTranslator.translate(...)` calls the sidecar first. If the sidecar reports
`ERROR` (unreachable, timed out, or returned a malformed/non-200 response), it calls the
rule-based translator instead and returns that result. A `CLARIFICATION_NEEDED` or
`INTENT_DRAFTED` result from the sidecar is returned as-is: falling back only makes sense
when the sidecar could not be reached or failed outright, not when it understood the request
well enough to draft an intent or ask a clarifying question.

### SidecarNlTranslator

An HTTP client for an external Python NL-to-intent SLM sidecar service. It POSTs

```json
{"text": "<nlText>", "context_hint": "<contextHint or null>"}
```

to `<baseUrl>/v1/translate` and expects a JSON response shaped

```json
{
  "status": "INTENT_DRAFTED | CLARIFICATION_NEEDED | ERROR",
  "draft_intent_json": "...",
  "clarification_questions": ["..."],
  "confidence": 0.0
}
```

Any `IOException`, timeout, non-200 response, or malformed response body is logged at WARN
and turned into `TranslationResult.error()` rather than thrown, so a caller such as
`FallbackNlTranslator` can fall back without having to guard against exceptions.

The sidecar's real address/port (host, port, TLS, auth) is a **deployment-time
configuration concern**: `lighty-intent-core` (or the app aggregator that wires
`lighty-intent` together) is responsible for constructing `SidecarNlTranslator` with the
actual base URL of the running sidecar, and for wrapping it in a `FallbackNlTranslator`
together with a `RuleBasedNlTranslator`. This module only builds the client; it does not
wire it into any lighty application.

### RuleBasedNlTranslator

A deterministic, offline, regex/keyword-based translator that never calls out to any
external service. It covers exactly the two intent-driven-management MVP use cases:

- **Transport QoS/isolation** (domain `lighty-intent-transport:transport`):
  - a number followed by `Mbps`/`mbps` → `min-bandwidth` (`IS_GREATER_THAN`)
  - a number followed by `ms` (e.g. "under 20 ms", "less than 20 ms") → `max-latency`
    (`IS_LESS_THAN`)
  - `isolate`/`isolated`/`dedicated` → `traffic-isolation` (`IS_EQUAL_TO` `"true"`)
  - the site/link the targets apply to is taken from a `"between X and Y"` or `"for X"`
    phrase in the text, when present, and used as `expectation-object-instance`.
- **RAN coverage/capacity** (domain `lighty-intent-ran-cco:ran`):
  - `"PRB utilization"`/`"prb utilization"` plus a percentage → `prb-utilization-max`
    (`IS_LESS_THAN`), with the cell/site name (from a `"for X"` phrase) as
    `expectation-object-instance`.

When the input contains neither a recognizable numeric target nor a known keyword, it
returns `TranslationResult.clarificationNeeded(...)` naming what is missing, instead of
guessing at a draft intent.

Output is RFC 7951 JSON matching `/intents/intent` from `lighty-intent.yang`, e.g.:

```json
{
  "lighty-intent:intent": [
    {
      "intent-id": "…",
      "intent-name": "NL-drafted intent",
      "domain": "lighty-intent-transport:transport",
      "source": "NL_SLM",
      "original-nl-text": "Ensure at least 200 Mbps between site-A and site-B, ...",
      "expectation": [
        {
          "expectation-id": "…",
          "expectation-verb": "ENSURE",
          "expectation-target": [
            {
              "expectation-object-type": "lighty-intent-transport:min-bandwidth",
              "expectation-object-instance": "site-A-to-site-B",
              "target-condition": "IS_GREATER_THAN",
              "target-value-range": ["200"],
              "unit": "Mbps"
            }
          ]
        }
      ]
    }
  ]
}
```

### FallbackNlTranslator

Takes a primary and a secondary `NlTranslator` in its constructor and implements the
try-sidecar-then-fall-back-to-rules policy described above.

## What this module does *not* do

- It does not run or manage the Python SLM sidecar process; that is a separate deployment
  artifact.
- It does not decide the sidecar's base URL, timeouts, or credentials; those are supplied by
  whoever constructs `SidecarNlTranslator` (`lighty-intent-core` / the app aggregator).
- It does not write to the intent datastore or call any other RPC; `translate-nl` only
  produces a draft for the caller to review.
