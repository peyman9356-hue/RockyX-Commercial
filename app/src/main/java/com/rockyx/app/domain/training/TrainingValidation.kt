package com.rockyx.app.domain.training

object TrainingValidation {
 fun validateSession(session:TrainingSession):List<String> = buildList {
  if(session.sessionId.isBlank())add("SESSION_ID_REQUIRED")
  if(session.dogId.isBlank())add("DOG_ID_REQUIRED")
  if(session.skillId.isBlank())add("SKILL_ID_REQUIRED")
  if(session.contextId.isBlank())add("CONTEXT_ID_REQUIRED")
  val ids=session.attempts.map{it.attemptId}
  if(ids.size!=ids.toSet().size)add("DUPLICATE_ATTEMPT_ID")
  if(session.status==SessionStatus.INVALID||session.status==SessionStatus.REJECTED)add("SESSION_NOT_EVALUABLE:"+session.status)
  session.attempts.forEach{a->
   if(a.sessionId!=session.sessionId)add("ATTEMPT_SESSION_MISMATCH:"+a.attemptId)
   if(a.dogId.isNotBlank()&&a.dogId!=session.dogId)add("ATTEMPT_DOG_MISMATCH:"+a.attemptId)
   if(a.sequenceNumber<1)add("INVALID_ATTEMPT_SEQUENCE:"+a.attemptId)
   if(a.clientGeneratedId.isBlank())add("ATTEMPT_CLIENT_GENERATED_ID_REQUIRED:"+a.attemptId)
   if(a.evidenceIds.any(String::isBlank))add("BLANK_EVIDENCE_ID:"+a.attemptId)
  }
 }
 fun validateEvidence(session:TrainingSession,evidence:List<SitEvidence>):List<String> = buildList {
  val attemptMap=session.attempts.associateBy{it.attemptId}
  val clientIds=mutableSetOf<String>()
  evidence.forEach{e->
   if(e.evidenceId.isBlank())add("EVIDENCE_ID_REQUIRED")
   if(e.clientGeneratedId.isBlank())add("CLIENT_GENERATED_ID_REQUIRED:"+e.evidenceId)
   if(!clientIds.add(e.clientGeneratedId))add("DUPLICATE_CLIENT_GENERATED_ID:"+e.clientGeneratedId)
   if(e.dogId!=session.dogId)add("EVIDENCE_DOG_MISMATCH:"+e.evidenceId)
   if(e.sessionId!=session.sessionId)add("EVIDENCE_SESSION_MISMATCH:"+e.evidenceId)
   val a=attemptMap[e.attemptId]
   if(a==null)add("UNKNOWN_ATTEMPT:"+e.evidenceId) else if(e.evidenceId !in a.evidenceIds)add("EVIDENCE_NOT_REFERENCED_BY_ATTEMPT:"+e.evidenceId)
  }
 }
 fun validateRuleAndPolicy(rule:RuleVersion,policy:PolicyVersion,session:TrainingSession):List<String> = buildList {
  if(rule.status!="VALID")add("INVALID_RULE_VERSION:"+rule.ruleId+":"+rule.version)
  if(policy.status!="VALID")add("INVALID_POLICY_VERSION:"+policy.policyVersionId)
  if(session.ruleVersionId!=rule.ruleId+":"+rule.version)add("RULE_VERSION_PIN_MISMATCH")
  if(session.policyVersionId!=policy.policyVersionId)add("POLICY_VERSION_PIN_MISMATCH")
 }
 fun validateConcurrentSupersede(evidence:List<SitEvidence>):List<String> = buildList {
  evidence.filter{it.supersedesEvidenceId!=null}.groupBy{it.supersedesEvidenceId!!}.filterValues{it.size>1}.keys.forEach{add("CONFLICTING_SUPERSEDES:"+it)}
 }
}
