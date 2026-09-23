package com.rockyx.app.domain.training

@Deprecated("Use TrainingPolicyEngine and SkillStateProjector as the Gate 1 authorities.")
object TrainingDecisionEngine {
 const val ENGINE_VERSION="GATE1_COMPATIBILITY_FACADE_V1"
 fun deriveSkillState(dogId:String,skillId:String,evaluation:Evaluation,evidence:List<SitEvidence>):SkillState=SkillStateProjector.project(dogId,skillId,evaluation,evidence)
 fun decide(dogId:String,skillId:String,evaluation:Evaluation,state:SkillState,decisionId:String="decision-"+evaluation.evaluationId):Decision=TrainingPolicyEngine.decide(dogId,skillId,evaluation,decisionId)
 fun nextAction(decision:Decision,state:SkillState):NextAction=when(decision.type){
  DecisionType.ADVANCE->NextAction(decision.decisionId,"Increase the challenge","Move to a slightly more difficult context and test again.")
  DecisionType.REPEAT->NextAction(decision.decisionId,"Repeat the exercise","Repeat the current exercise and record the next valid attempts.")
  DecisionType.REVIEW->NextAction(decision.decisionId,"Review the exercise","Check the instruction and record another valid practice session.")
  else->NextAction(decision.decisionId,"Continue practice","Repeat the current exercise and record the next valid attempts.")
 }
}
