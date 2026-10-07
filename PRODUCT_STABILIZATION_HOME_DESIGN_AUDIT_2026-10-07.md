# Rocky X — Product Stabilization / Home Design Audit
Date: 2026-10-07
Branch: product-stabilization-home-2026-10-07
Base: 28bc6a84ab0935e8be48abb20cdbef4db05f6ad6

## Scope

This branch is for Product Stabilization before the next infrastructure stage.

Primary scope:
- Home redesign
- Shell/navigation corrections
- confirmed UI/functionality defects observed on the current APK
- real product behavior and content integration
- meaningful milestone APK verification

This branch must not redesign Gate 1 semantics or training-domain authority.

## Home Reference

The user-approved visual direction is the attached Living Training Gem concept.

Core Home structure:
- central realistic American Akita / Rocky identity
- Living Training Gem as the visual state language
- primary action: شروع تمرین امروز
- Training Continuum / مسیر پیوسته تمرین
- Coach Insight / دیدگاه مربی
- Recent Activity / فعالیت‌های اخیر
- Rocky X Coach composer
- More on LEFT
- Library on RIGHT
- RTL Persian
- Android portrait safe areas

## Living Training Gem

The Gem is not decorative-only. Its eventual visual state must be derived from:
Evidence -> Evaluation -> Training State -> Policy -> Next Action -> Visual State

The UI must not invent progress, mastery, percentages, badges, streaks, or success claims without supporting product state/evidence.

Future interaction direction:
- Rocky remains the same individual dog.
- subtle natural idle presence is desirable.
- blink/yawn/micro-movement may be implemented as a later asset/interaction layer.
- pressing شروع تمرین امروز may produce a restrained attentive/happy reaction.
- no cartoon behavior, human smile, sci-fi HUD, or excessive animation.

## Confirmed Current Product Defects

1. Bottom navigation items گفتگو and حساب کاربری are partly behind the Android system navigation area and are not reliably clickable.
2. Library drawer is on the wrong side for RTL; required side is RIGHT.
3. Library drawer animation travels toward the opposite side before settling; required behavior is direct RIGHT -> inward on open and inward -> RIGHT on close.
4. More drawer is on the wrong side; required side is LEFT.
5. More drawer animation has the same wrong-direction transition; required behavior is direct LEFT -> inward on open and inward -> LEFT on close.
6. زبان دستگاه in Language & Region is visibly present but functionally inert; selecting it does not present a language list.
7. Home currently needs a substantive visual/product redesign, not cosmetic polishing.

## Shell Contract

RTL:
- More = LEFT
- Library = RIGHT
- back navigation remains consistent with RTL
- bottom controls must remain above the Android system navigation safe area

Drawer motion:
- one coherent drawer mechanism
- no cross-screen/opposite-side travel
- preserve underlying Home state while drawer is open

## Product Verification Rule

A Home milestone is not Product Verified merely because source changes compile.

Required milestone sequence:
Audit -> Minimum Fix -> Tests/CI -> APK -> user installs/observes -> Product Verification -> next milestone

Do not require an APK for every tiny change. Build only after a meaningful block of related changes.

## Non-Goals

- no Gate 1 redesign
- no replacement of deterministic training authority with AI
- no fake training statistics
- no generic dashboard/card proliferation
- no next infrastructure stage until this Product Stabilization milestone is meaningfully verified
