# Headphone correction data: licence review (2026-11-22)

« Égaliseur par casque » (9.0) plays parametric corrections the listener imports
(Equalizer APO text: `Preamp`, `Filter … PK/LSC/HSC`). Ketvoice ships, bundles or
downloads **no** headphone measurement or correction data, and no AutoEq code.
The filters are Ketvoice's own implementation of the formulas of Robert
Bristow-Johnson's « Audio EQ Cookbook » (`player/Biquad.kt`).

Why no catalogue is built in:

| Source | Licence found | Usable in Ketvoice (closed, source-available app)? |
| --- | --- | --- |
| AutoEq code, https://github.com/jaakkopasanen/AutoEq/blob/master/LICENSE | MIT | Not needed (not used). |
| AutoEq results (`results/`) | None. The maintainer: the MIT licence « was intended to cover only the code, and not the data » (https://github.com/jaakkopasanen/AutoEq/issues/790, 2025-03-30); « I don't own the measurement data and therefore cannot technically give permission to distribute it » (https://github.com/jaakkopasanen/AutoEq/issues/1101, 2026-11-11). | No: no licence allows redistribution or use. |
| Measurements behind them (oratory1990, crinacle, Rtings, Innerfidelity, …) | oratory1990's list says « Unlicensed commercial use is prohibited » (quoted in issue 1101); no reuse licence found for the others. | No, without each owner's written permission. |
| OPRA, https://github.com/opra-project/OPRA (README) | Data labelled CC BY-SA 4.0, but its entries were imported from AutoEq « as it is already released under a permissive license » and from oratory1990's PDFs (PRs 10 and 70, no recorded permission). The free mirror is offered for « open source, non-commercial, and personal use » (docs/CONSUMING.md). | No: the CC BY-SA label does not rest on the measurement owners' permission, and Ketvoice is not open source. |

A built-in catalogue (search among thousands of models, fetched on demand) needs
written permission from the owners of the measurements it would use, or a data
set whose licence comes from them. Until then, none is added.
