package com.rockyx.app.domain.training

class TrainingVersionRegistry(rules:Collection<RuleVersion>,policies:Collection<PolicyVersion>){
 private val rulesById=rules.associateBy{it.ruleId+":"+it.version}
 private val policiesById=policies.associateBy{it.policyVersionId}
 init{require(rulesById.size==rules.size){"Duplicate RuleVersion identity."};require(policiesById.size==policies.size){"Duplicate PolicyVersion identity."}}
 fun requireRule(id:String):RuleVersion=rulesById[id]?.takeIf{it.status=="VALID"}?:error("Unknown or invalid RuleVersion: "+id)
 fun requirePolicy(id:String):PolicyVersion=policiesById[id]?.takeIf{it.status=="VALID"}?:error("Unknown or invalid PolicyVersion: "+id)
 fun requireExactPins(session:TrainingSession):Pair<RuleVersion,PolicyVersion>{val r=requireRule(session.ruleVersionId);val p=requirePolicy(session.policyVersionId);require(session.ruleVersionId==r.ruleId+":"+r.version);require(session.policyVersionId==p.policyVersionId);return r to p}
}
class ClientEventIdentityRegistry{
 private val fingerprints=mutableMapOf<String,String>()
 fun accept(clientGeneratedId:String,contentFingerprint:String):Boolean{require(clientGeneratedId.isNotBlank());val e=fingerprints[clientGeneratedId];if(e==null){fingerprints[clientGeneratedId]=contentFingerprint;return true};require(e==contentFingerprint){"CLIENT_ID_REUSE_WITH_DIFFERENT_CONTENT:"+clientGeneratedId};return false}
}
class ImmutableEvidenceHistory{
 private val records=linkedMapOf<String,SitEvidence>();private val successor=mutableMapOf<String,String>()
 fun append(e:SitEvidence){require(e.evidenceId.isNotBlank());require(e.evidenceId !in records){"Evidence is immutable: duplicate evidenceId."};e.supersedesEvidenceId?.let{p->require(p in records){"Superseded evidence must already exist."};require(p !in successor){"CONCURRENT_SUPERSEDE:"+p};require(p!=e.evidenceId);successor[p]=e.evidenceId};records[e.evidenceId]=e}
 fun all(): List<SitEvidence> =records.values.toList()
}
class ImmutableEvaluationHistory{private val records=linkedMapOf<String,Evaluation>();fun append(e:Evaluation){require(e.evaluationId !in records){"Evaluation is immutable: duplicate evaluationId."};records[e.evaluationId]=e};fun get(id:String)=records[id];fun all()=records.values.toList()}
class ImmutableDecisionHistory{private val records=linkedMapOf<String,Decision>();fun append(d:Decision){require(d.decisionId !in records){"Decision is immutable: duplicate decisionId."};require(d.basisEvaluationIds==d.basisEvaluationIds.sorted()){"basisEvaluationIds must be lexicographically sorted."};records[d.decisionId]=d};fun get(id:String)=records[id];fun all()=records.values.toList()}
data class Assessment(val dogId:String,val skillId:String,val latestEvaluationId:String?,val latestDecisionId:String?)
object AssessmentProjector{fun project(dogId:String,skillId:String,evaluations:List<Evaluation>,decisions:List<Decision>):Assessment{val e=evaluations.filter{it.sessionId.isNotBlank()}.lastOrNull();return Assessment(dogId,skillId,e?.evaluationId,decisions.lastOrNull()?.decisionId)}}
object DecisionValidation{fun validate(d:Decision,evaluations:Collection<Evaluation>): List<String> =buildList{if(d.basisEvaluationIds!=d.basisEvaluationIds.sorted())add("BASIS_EVALUATION_IDS_NOT_SORTED");val known=evaluations.map{it.evaluationId}.toSet();d.basisEvaluationIds.filterNot{it in known}.forEach{add("UNKNOWN_BASIS_EVALUATION:"+it)};evaluations.filter{it.evaluationId in d.basisEvaluationIds}.filter{it.policyVersionId!=d.policyVersionId}.forEach{add("BASIS_POLICY_VERSION_MISMATCH:"+it.evaluationId)}}}
object HistoricalVersionGuard{fun validate(e:Evaluation,registry:TrainingVersionRegistry){registry.requireRule(e.ruleVersionId);registry.requirePolicy(e.policyVersionId)}}


data class TrainingSyncEvent(
    val clientGeneratedId: String,
    val recordType: String,
    val recordId: String,
    val canonicalPayload: String,
    val ruleVersionId: String,
    val policyVersionId: String,
    val supersedesRecordId: String? = null,
    val sessionId: String = "",
    val dogId: String = "",
    val attemptId: String = "",
    val evidenceIds: List<String> = emptyList(),
    val attemptIds: List<String> = emptyList(),
    val basisEvaluationIds: List<String> = emptyList()
)
data class TrainingSyncEnvelope(
    val sessionId: String,
    val dogId: String,
    val ruleVersionId: String,
    val policyVersionId: String,
    val events: List<TrainingSyncEvent>
)

enum class SyncRejectionCode { EMPTY_SESSION, INVALID_SESSION, VERSION_MISMATCH, EMPTY_EVENT_ID, EVENT_VERSION_MISMATCH, EVENT_SESSION_MISMATCH, EVENT_DOG_MISMATCH, MISSING_ATTEMPT_REFERENCE, MISSING_EVIDENCE_REFERENCE, BASIS_EVALUATION_IDS_NOT_SORTED, UNSUPPORTED_RECORD_TYPE }

data class SyncValidationResult(val accepted: Boolean, val rejections: List<SyncRejectionCode>)
data class SyncApplyResult(val accepted: Boolean, val acceptedEventIds: List<String>, val duplicateEventIds: List<String>, val rejections: List<String>)

object TrainingSyncValidator {
    fun validate(envelope: TrainingSyncEnvelope, session: TrainingSession, registry: TrainingVersionRegistry): SyncValidationResult {
        val errors = buildList {
            if (envelope.sessionId.isBlank() || envelope.dogId.isBlank()) add(SyncRejectionCode.EMPTY_SESSION)
            try { registry.requireExactPins(session) } catch (_: RuntimeException) { add(SyncRejectionCode.INVALID_SESSION) }
            if (envelope.sessionId != session.sessionId || envelope.dogId != session.dogId ||
                envelope.ruleVersionId != session.ruleVersionId || envelope.policyVersionId != session.policyVersionId) {
                add(SyncRejectionCode.VERSION_MISMATCH)
            }
            envelope.events.forEach { event ->
                if (event.clientGeneratedId.isBlank()) add(SyncRejectionCode.EMPTY_EVENT_ID)
                if (event.sessionId != session.sessionId) add(SyncRejectionCode.EVENT_SESSION_MISMATCH)
                if (event.recordType !in setOf("SESSION", "ATTEMPT", "EVIDENCE", "EVALUATION", "DECISION")) add(SyncRejectionCode.UNSUPPORTED_RECORD_TYPE)
                if (event.recordType == "ATTEMPT" && event.dogId.isNotBlank() && event.dogId != session.dogId) add(SyncRejectionCode.EVENT_DOG_MISMATCH)
                if (event.recordType == "EVIDENCE" && event.attemptId.isBlank()) add(SyncRejectionCode.MISSING_ATTEMPT_REFERENCE)
                if (event.recordType == "EVALUATION" && (event.attemptIds.isEmpty() || event.evidenceIds.isEmpty())) add(SyncRejectionCode.MISSING_EVIDENCE_REFERENCE)
                if (event.recordType == "DECISION" && event.basisEvaluationIds != event.basisEvaluationIds.sorted()) add(SyncRejectionCode.BASIS_EVALUATION_IDS_NOT_SORTED)
                if (event.ruleVersionId != session.ruleVersionId || event.policyVersionId != session.policyVersionId) {
                    add(SyncRejectionCode.EVENT_VERSION_MISMATCH)
                }
            }
        }
        return SyncValidationResult(errors.isEmpty(), errors.distinct())
    }
}
