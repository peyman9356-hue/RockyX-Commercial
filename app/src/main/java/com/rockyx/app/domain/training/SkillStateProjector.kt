package com.rockyx.app.domain.training

object SkillStateProjector{
 fun project(dogId:String,skillId:String,evaluation:Evaluation,evidence:List<SitEvidence>):SkillState{
  val valid=evidence.filter{it.evidenceId in evaluation.evidenceIds&&it.status==EvidenceStatus.VALID}
  val acquisition=when{evaluation.sufficiency!=Sufficiency.SUFFICIENT->SkillAcquisition.UNKNOWN;evaluation.result==EvaluationResult.SUCCESS->SkillAcquisition.RELIABLE;evaluation.result==EvaluationResult.PARTIAL->SkillAcquisition.EMERGING;evaluation.result==EvaluationResult.FAIL->SkillAcquisition.NOT_ESTABLISHED;else->SkillAcquisition.UNKNOWN}
  val mastery=when(acquisition){SkillAcquisition.RELIABLE,SkillAcquisition.STRONG->MasteryStatus.ACQUIRED;SkillAcquisition.EMERGING->MasteryStatus.NOT_MASTERED;SkillAcquisition.NOT_ESTABLISHED->MasteryStatus.REGRESSION;SkillAcquisition.UNKNOWN->MasteryStatus.NOT_MASTERED}
  return SkillState(dogId,skillId,acquisition,mastery,evaluation.sessionId,buildList{add("EVALUATION:"+evaluation.evaluationId);if(valid.isEmpty())add("NO_VALID_EVIDENCE")})
 }
}
