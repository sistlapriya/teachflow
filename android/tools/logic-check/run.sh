#!/usr/bin/env bash
# Device-independent checks of TeachFlow's logic. NOT a substitute for device tests.
#   1. Harness.kt   — parser, skill matcher, relevance filter, synthesizer, grounder on synthetic screens.
#   2. executor/    — the real WorkflowExecutor driven against a scripted fake food app:
#                     replay, new item, quantity, address, popup, already-in-cart, recovery exhausted,
#                     empty results, ambiguity → parameter resolved, authentication boundary → Resume.
#                     Fails if any scenario touches a credential field or the payment button.
# Needs kotlinc and an android.jar (API >= 30), used for types only.
# Usage: ANDROID_JAR=/path/to/android.jar ./run.sh
set -e
cd "$(dirname "$0")"
S=../../app/src/main/java/com/teachflow/agent
CORE="$S/accessibility/NodeSnapshot.kt $S/accessibility/SemanticRoleClassifier.kt $S/accessibility/ActionObserver.kt \
  $S/accessibility/AccessibilityTreeParser.kt $S/nlu/TextMatch.kt $S/nlu/CommandParser.kt $S/skills/Skill.kt \
  $S/skills/SkillMatcher.kt $S/learning/*.kt $S/execution/SemanticGrounder.kt $S/execution/WorkflowExecutor.kt \
  $S/execution/RunReport.kt $S/execution/ActionExecutor.kt $S/recovery/*.kt $S/safety/*.kt $S/core/*.kt"
kotlinc -nowarn runtime/Log.kt -d /tmp/tf-shadow
kotlinc -cp "$ANDROID_JAR" -nowarn $CORE runtime/coroutines.kt runtime/flow.kt Harness.kt -include-runtime -d /tmp/tf-logic.jar
kotlinc -cp "$ANDROID_JAR" -nowarn $CORE runtime/coroutines.kt runtime/flow.kt executor/FakeApp.kt executor/Scenarios.kt -include-runtime -d /tmp/tf-exec.jar
kotlinc -cp "$ANDROID_JAR" -nowarn $CORE runtime/coroutines.kt runtime/flow.kt executor/FakeApp.kt executor/Scenarios.kt executor/MissingDetailCheck.kt -include-runtime -d /tmp/tf-md.jar
kotlinc -cp "$ANDROID_JAR" -nowarn $CORE runtime/coroutines.kt runtime/flow.kt executor/RelevanceCheck.kt -include-runtime -d /tmp/tf-rel.jar
echo "===== 1. Logic harness ====="; java -cp "/tmp/tf-shadow:/tmp/tf-logic.jar:$ANDROID_JAR" HarnessKt
echo "===== 2. Executor scenarios (fake app) ====="; java -cp "/tmp/tf-shadow:/tmp/tf-exec.jar:$ANDROID_JAR" ScenariosKt
echo "===== 3. Missing-detail check ====="; java -cp "/tmp/tf-shadow:/tmp/tf-md.jar:$ANDROID_JAR" MissingDetailCheckKt
echo "===== 4. Relevance filter (RELEVANT / UNCERTAIN / IRRELEVANT) ====="; java -cp "/tmp/tf-shadow:/tmp/tf-rel.jar:$ANDROID_JAR" RelevanceCheckKt
