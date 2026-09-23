package com.rockyx.app.domain.training

object TrainingPolicyEngine{
 const val POLICY_ID="SIT_POLICY"
 const val VERSION="1"
 fun decide(dogId:String,skillId:String,evaluation:Evaluation,decisionId:String="decision-"+evaluation.evaluationId):Decision{
  require(evaluation.policyVersionId==POLICY_ID+":"+VERSION){"Unsupported policy version: "+evaluation.policyVersionId}
  val type=when(evaluation.sufficiency){
   Sufficiency.SUFFICIENT->when(evaluation.result){EvaluationResult.SUCCESS->DecisionType.ADVANCE;EvaluationResult.FAIL,EvaluationResult.PARTIAL->DecisionType.REPEAT;EvaluationResult.UNKNOWN->DecisionType.REVIEW}
   Sufficiency.INSUFFICIENT,Sufficiency.CONTRADICTORY->DecisionType.REVIEW
  }
  return Decision(decisionId,dogId,skillId,evaluation.policyVersionId,listOf(evaluation.evaluationId).sorted(),type,listOf("POLICY:"+evaluation.policyVersionId,"EVALUATION:"+evaluation.evaluationId))
 }
}
