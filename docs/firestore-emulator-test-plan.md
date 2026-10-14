# Firestore Rules Emulator test plan

This branch is a non-production test harness plan. Do not deploy Firestore rules until tests pass and existing teacher/student flows are regression-tested.

## Required regression cases

- A student cannot change their own profile role to TEACHER.
- A student cannot create a forged shared-material payload.
- Unauthenticated access to legacy shared links is reviewed explicitly.
- Hidden or unpublished classes cannot be read through the discussion feature.
- Users cannot read or write another teacher's private materials.
- Only the question author can edit their question.
- Only a post author can edit their post.
- Only an authorized teacher can change thread status.
- Edit-history entries cannot be modified or deleted by clients.

## Safety status

No production Firestore rules are changed by this plan. These cases must be implemented and run with the Firestore Rules Emulator before security rules or discussion writes are enabled.
