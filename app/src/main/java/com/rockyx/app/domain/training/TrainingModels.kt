package com.rockyx.app.domain.training

enum class CueType { VERBAL, HAND_SIGNAL, VERBAL_AND_HAND_SIGNAL }
enum class LureStatus { REQUIRED, NOT_REQUIRED }
enum class SitResult { YES, NO }
enum class ResponseQuality { IMMEDIATE, DELAYED, REPROMPT_REQUIRED }
enum class RewardTiming { IMMEDIATE, LATE, NOT_DELIVERED }
enum class ContextType { HOME_CALM, OUTSIDE_CALM, OUTSIDE_DISTRACTION, UNKNOWN }
enum class EvidenceStatus { VALID, INVALID }
enum class SessionStatus { ACTIVE, COMPLETED, INVALID, REJECTED }
enum class Sufficiency { SUFFICIENT, INSUFFICIENT, CONTRADICTORY }
enum class EvaluationResult { SUCCESS, FAIL, PARTIAL, UNKNOWN }
enum class EvaluationStatus { VALID, REVIEW_REQUIRED, SUPERSEDED, PENDING_REMOTE_EVALUATION, INSUFFICIENT }
enum class ConfidenceTier { LOW, MEDIUM, HIGH }
enum class SkillAcquisition { NOT_ESTABLISHED, EMERGING, RELIABLE, STRONG, UNKNOWN }
enum class MasteryStatus { NOT_MASTERED, ACQUIRED, MAINTENANCE, REGRESSION, REFRESH_NEEDED }
enum class DecisionType { START, REPEAT, SIMPLIFY, ADVANCE, MAINTAIN, REFRESH, REVIEW, SAFETY_ESCALATION, NO_ACTION }

data class TrainingContext(val contextId:String,val type:ContextType)
data class RuleVersion(val ruleId:String,val version:String,val status:String="VALID",val definition:String="")
data class PolicyVersion(val policyId:String,val version:String,val status:String="VALID",val definition:String=""){val policyVersionId:String get()=policyId+":"+version}
data class SitEvidence(val evidenceId:String,val dogId:String,val attemptId:String,val sessionId:String,val contextId:String,val cueType:CueType,val lureStatus:LureStatus,val sitResult:SitResult,val responseQuality:ResponseQuality,val rewardTiming:RewardTiming,val status:EvidenceStatus=EvidenceStatus.VALID,val schemaVersion:Int=1,val clientGeneratedId:String=evidenceId,val supersedesEvidenceId:String?=null)
data class TrainingAttempt(val attemptId:String,val sessionId:String,val sequenceNumber:Int,val evidenceIds:List<String>,val dogId:String,val createdAt:Long,val clientGeneratedId:String)
data class TrainingSession(val sessionId:String,val dogId:String,val skillId:String,val contextId:String,val attempts:List<TrainingAttempt>,val lessonId:String="sit",val lessonVersion:Int=1,val exerciseId:String="sit-practice",val exerciseVersion:Int=1,val ruleVersionId:String="SIT_AGGREGATION_V1_PROTOTYPE:1",val policyVersionId:String="SIT_POLICY:1",val createdAt:Long=0L,val status:SessionStatus=SessionStatus.ACTIVE)
data class Evaluation(val evaluationId:String,val sessionId:String,val attemptIds:List<String>,val evidenceIds:List<String>,val result:EvaluationResult,val sufficiency:Sufficiency,val status:EvaluationStatus,val confidenceTier:ConfidenceTier,val ruleVersionId:String,val policyVersionId:String,val evaluatorVersion:String="SELF_REPORT_V1",val createdAt:Long=0L)
data class SkillState(val dogId:String,val skillId:String,val acquisition:SkillAcquisition,val masteryStatus:MasteryStatus,val lastEvaluatedSessionId:String?,val reasonCodes:List<String>)
data class Decision(val decisionId:String,val dogId:String,val skillId:String,val policyVersionId:String,val basisEvaluationIds:List<String>,val type:DecisionType,val reasonCodes:List<String>,val createdAt:Long=0L)
data class NextAction(val decisionId:String,val title:String,val instruction:String)
