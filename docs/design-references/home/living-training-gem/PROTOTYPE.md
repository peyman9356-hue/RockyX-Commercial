# Rocky X — Living Training Gem Prototype

Status: PROTOTYPE / BUILD VERIFICATION PENDING

Reference:
LIVING-TRAINING-GEM-REF-01

## Visual contract

- Rocky is the fixed living core.
- The diamond/crystal field orbits around Rocky.
- Rocky does not rotate with the field.
- The field is a faceted diamond topology, not a spherical particle cloud.
- Back facets, Rocky, front facets and moving light/reflection are separate visual layers.
- Motion is slow and controlled.
- Home is an environment, not a generic dashboard.

## Engineering boundary

The renderer is presentation-only.

It must not own:
- TrainingSession
- Evidence
- Evaluation
- Assessment
- Decision
- Policy
- sync
- persistence

Future data flow:

Training State -> UI State Mapper -> LivingGemView

Current prototype API:
- setState(TrainingUiState)
- playEvent(GemEvent)
- setReducedMotion(Boolean)
- setRockyDrawable(Int)

## Current implementation

Android Views + Canvas.

The renderer is intentionally replaceable. If real-device evidence shows that Canvas cannot reach the required visual quality/performance, the renderer boundary can later host an OpenGL implementation without changing Home's domain boundary.

## Prototype asset warning

The current Rocky image is a small temporary prototype asset generated from the available Rocky cutout candidate. It is not the final commercial media asset and is replaceable.

## Verification gate

This prototype is not called successful until:
1. CI compiles the actual Rocky X source after overlay.
2. Debug APK is produced.
3. APK is installed on the user's real phone.
4. User observes the Home motion.
5. User compares it directly against LIVING-TRAINING-GEM-REF-01.
6. Any visual mismatch is recorded and fixed.
