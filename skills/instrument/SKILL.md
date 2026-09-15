---
name: instrument
description: Instrument this repository's agent so its traces reach Tessary and every model-call span carries a tessary.call_site.id attribute. Use when the user says "instrument this repo for tessary", "send traces to tessary", or invokes /instrument.
---

Fetch and follow the workflow at https://github.com/tessaryai/tessary/blob/main/instrument.md to wire this repository's tracing to Tessary's OTLP endpoint and tag every model-call span with `tessary.call_site.id`.
