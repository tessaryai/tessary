---
name: instrument
description: Instrument this repository's agent so its traces reach Tessary and every model-call span carries a tessary.call_site.id attribute. Use when the user says "instrument this repo for tessary", "send traces to tessary", or invokes /instrument.
---

Fetch and follow the workflow at https://github.com/tessaryai/tessary/blob/main/instrument.md. It guides the user one call site at a time: audit the shape of the existing traces, agree the target shape with the user, then wire tracing to Tessary's OTLP endpoint and tag the model-call span with `tessary.call_site.id`.
