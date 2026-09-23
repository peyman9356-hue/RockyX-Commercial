package com.rockyx.app.domain.training

object SitSessionAggregator{
 const val RULE_VERSION="SIT_AGGREGATION_V1_PROTOTYPE"
 fun activeEvidence(evidence:List<SitEvidence>):List<SitEvidence>{require(TrainingValidation.validateConcurrentSupersede(evidence).isEmpty()){"Conflicting evidence supersedes links."};val superseded=evidence.mapNotNull{it.supersedesEvidenceId}.toSet();return evidence.filter{it.status==EvidenceStatus.VALID&&it.evidenceId !in superseded}.sortedBy{it.clientGeneratedId}}
 fun evaluate(session:TrainingSession,evidence:List<SitEvidence>,evaluationId:String="eval-"+session.sessionId,ruleVersion:RuleVersion=RuleVersion("SIT_AGGREGATION_V1_PROTOTYPE","1"),policyVersion:PolicyVersion=PolicyVersion("SIT_POLICY","1")):Evaluation{
  require(TrainingValidation.validateSession(session).isEmpty()){"Invalid training session."}
  require(TrainingValidation.validateEvidence(session,evidence).isEmpty()){"Invalid training evidence."}
  require(TrainingValidation.validateRuleAndPolicy(ruleVersion,policyVersion,session).isEmpty()){"Pinned RuleVersion/PolicyVersion mismatch or invalid."}
  val valid=activeEvidence(evidence)
  if(valid.isEmpty())return Evaluation(evaluationId,session.sessionId,session.attempts.map{it.attemptId},emptyList(),EvaluationResult.UNKNOWN,Sufficiency.INSUFFICIENT,EvaluationStatus.INSUFFICIENT,ConfidenceTier.LOW,ruleVersion.ruleId+":"+ruleVersion.version,policyVersion.policyVersionId)
  val passes=valid.count{it.sitResult==SitResult.YES};val fails=valid.count{it.sitResult==SitResult.NO}
  val result=when{valid.size>=3&&passes==valid.size->EvaluationResult.SUCCESS;passes>fails->EvaluationResult.PARTIAL;fails>passes->EvaluationResult.FAIL;else->EvaluationResult.PARTIAL}
  val suff=if(valid.size>=3)Sufficiency.SUFFICIENT else Sufficiency.INSUFFICIENT
  return Evaluation(evaluationId,session.sessionId,session.attempts.map{it.attemptId},valid.map{it.evidenceId},result,suff,if(suff==Sufficiency.SUFFICIENT)EvaluationStatus.VALID else EvaluationStatus.INSUFFICIENT,if(valid.size>=3)ConfidenceTier.MEDIUM else ConfidenceTier.LOW,ruleVersion.ruleId+":"+ruleVersion.version,policyVersion.policyVersionId)
 }
}
