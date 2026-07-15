# Contributing

`cloud-itonami-isic-2392` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development
The capability layer lives in `kotoba-lang/*` libraries. This repo holds the
business blueprint and operator contracts.

```bash
clojure -M:test
clojure -M:lint
```

## Rules
- Do not commit real operating, personal or credential data.
- Keep maintenance scheduling, shipment records and disclosures behind the Clay Plant Operations Governor.
- Treat workflows as high-risk: add tests for equipment-control gating,
  record integrity, safety-concern escalation and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
