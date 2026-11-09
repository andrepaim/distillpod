"""Private, content-free evidence projection for a bounded distillation incident.

No rates, provider calls, notifications or semantic correctness claims.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
from uuid import UUID

IDS = ('capture_request_id','moment_id','generation_attempt_id','execution_id',
       'transcript_revision_id','previous_attempt_id')
LINK_IDS = IDS[:4]  # A transcript revision can legitimately be shared by unrelated captures.
FLAGS = ('context_expanded','transcript_changed','model_changed','prompt_changed','candidate_reused')
STAGE = ('stage','stage_outcome','generation_outcome','review_outcome','passage_complete',
         'takeaway_supported','relevant','quote_faithful','needs_context','contradictory_review',
         'quote_omitted','issue_incomplete_count','issue_unsupported_count','issue_irrelevant_count',
         'issue_quote_count','generator_model','reviewer_model','prompt_version',
         'generation_elapsed_ms','review_elapsed_ms','generation_attempted_calls','review_attempted_calls')
CONTEXT = ('transcript_source','outcome','context_reason','expanded_context','backward_window_ms',
           'forward_window_ms','duration_known','coverage_measured','coverage_complete',
           'missing_duration_ms','segments_measured','input_segment_count','usable_segment_count',
           'usable_span_count','context_text_units','excluded_left_count','excluded_right_count',
           'frozen_context','preparation_elapsed_ms')
COUNTS = ('input_segment_count','usable_segment_count','usable_span_count','context_text_units','excluded_left_count','excluded_right_count')
HEALTH = ('budget_policy_version','budget_day','daily_limit','pending_envelopes','dropped_reports',
          'routine_limit','routine_used','routine_dropped','critical_limit','critical_used',
          'critical_dropped','health_limit','health_used','health_dropped','errors_limit',
          'errors_used','errors_dropped')
COMMON = ('schema_version','correlation_version','correlation_complete','retry_trigger',
          'feature','operation_id','transcript_id','sequence','outcome','reason','source',
          'operation_release','operation_started_ms','elapsed_ms')
FIELDS = tuple(dict.fromkeys(('message','timestamp','summary_id','event_id','installation_id',
          'observed_at_ms','emitted_at_ms','outcome_version','context_schema_version','test_traffic')
          + IDS + FLAGS + STAGE + CONTEXT + HEALTH + COMMON))
LIMITS = ('Content-free telemetry explains the recorded pipeline decision; it cannot independently '
          'prove semantic correctness or reconstruct historical data never collected. Complete API '
          'pagination is not complete application observation: consent, retention, delayed delivery, '
          'queue/provider loss and budgets can hide evidence. Budget counters include suppressed '
          'pending summaries and are not proof of permanent loss or unique lost operations. '
          'Context snapshots are not a preparation count: successfully queued unchanged '
          'transcript_required waits may be coalesced. Missing rows do not identify the cause. '
          'Context and attempt summaries are best-effort direct details; generic terminal '
          'journal replay within retention does not restore missing stage/context details. '
          'Attempted calls are execution gateway invocations, not billing. Coverage is interval '
          'coverage, not semantic quality. No success/failure rate is computed.')

def random_id(value):
    try:
        parsed=UUID(value)
        return parsed.version==4 and str(parsed)==value
    except (ValueError,TypeError,AttributeError):
        return False

def number(value):
    if isinstance(value,bool):return None
    try:
        if isinstance(value,float) and not value.is_integer():return None
        return int(value) if value is not None else None
    except (ValueError,TypeError,OverflowError):return None

def version(row,key,expected):return number(row.get(key))==expected


# Mirrors producer allowlists; new categories require explicit consumer review.
EVENTS=set(('capture.started capture.completed capture.requested capture.accepted capture.saved '
 'capture.waiting capture.transcript_link capture.prepared capture.failed capture.context_summary '
 'transcription.quality transcription.partial transcription.paused transcription.cancelled '
 'transcription.dependency transcription.started transcription.stage transcription.queued '
 'transcription.completed transcription.failed distillation.discarded distillation.queued '
 'distillation.started distillation.finished distillation.failed distillation.cancelled '
 'distillation.attempt_summary operation.summary diagnostics.health').split())
REASONS=set(('restricted throttled formats busy partial superseded reconciled process_interrupted '
 'buffering ended resume_succeeded resume_failed none created deduplicated transcript_required '
 'waiting waiting_auth waiting_quota waiting_transcript queued unknown no_speech needs_context '
 'review_source unclear_source succeeded failed ready rejected disabled accepted duplicate cancelled '
 'repeat untrusted charging_required battery_low offline wifi_required scheduled online publisher '
 'downloading checking_model decoding starting transcribing saving playing paused phone car headphones '
 'resume_requested resume_cancelled dns timeout permission auth_expired quota network_or_storage '
 'invalid_data internal synthetic').split())
OUTCOMES=set(('not_run unknown cached ready accepted generator_needs_context generator_unclear_source '
 'review_rejected invalid_response interrupted cancelled auth_failure quota request_failed '
 'stale_result_suppressed internal_failure published published_without_quote').split())
MODELS=set(('gpt-4o gpt-4o-mini gpt-4.1 gpt-4.1-mini gpt-4.1-nano gpt-5 gpt-5-mini gpt-5-nano '
 'gpt-5.1 gpt-5.2 gpt-5.3 gpt-5.4 gpt-5-codex gpt-5.1-codex gpt-5.1-codex-mini '
 'gpt-5.2-codex gpt-5.3-codex o1 o1-mini o3 o3-mini o3-pro o4-mini unknown not_run').split())
ENUMS=dict(reason=REASONS,source={'background','phone','headphones','car'},
 feature={'capture','transcription','distillation','playback','voice'},
 stage={'generation','review','publication'},stage_outcome=OUTCOMES,
 generation_outcome=OUTCOMES,review_outcome=OUTCOMES,
 generator_model=MODELS,reviewer_model=MODELS,
 retry_trigger={'initial','explicit_retry','expanded_context','transcript_changed','review_resume','process_recovery','unknown'},
 transcript_source={'publisher','whisper','online','missing','other'},
 context_reason=set('ready coverage_missing source_mismatch revision_mismatch empty_speech empty_context oversized invalid_bounds episode_missing superseded cancelled internal'.split()),
 outcome=set('queued running waiting paused completed partial failed cancelled interrupted unknown waiting_auth transcript_required no_speech needs_context review_source superseded episode_missing'.split()))
BOOLEAN_FIELDS=set(FLAGS+('test_traffic','correlation_complete','passage_complete','takeaway_supported',
 'relevant','quote_faithful','needs_context','contradictory_review','quote_omitted','expanded_context',
 'duration_known','coverage_measured','coverage_complete','segments_measured','frozen_context'))
IDENTIFIERS=set(IDS+('summary_id','event_id','installation_id','operation_id','transcript_id'))
WARNING_CODES=set('missing_batch_identity batch_identity_conflict batch_snapshot_mismatch row_cap page_cap api_read_failed invalid_api_response partial_api_response invalid_api_row unsafe_pagination repeated_cursor invalid_pagination changed_pagination_scope missing_pagination_cursor pagination_metadata_missing unexpected_response_url invalid_observation_metadata'.split())

def safe_date(value):
    from datetime import datetime
    if not isinstance(value,str) or len(value)>35:return None
    try:
        parsed=datetime.fromisoformat(value.replace('Z','+00:00'))
        return parsed.isoformat() if parsed.utcoffset() is not None else None
    except ValueError:return None

def safe_value(field,value):
    import re
    if field in IDENTIFIERS:return value if random_id(value) else None
    if field in BOOLEAN_FIELDS:
        if isinstance(value,bool):return value
        if value in ('true','false'):return value=='true'
        return 'unknown' if value=='unknown' else None
    if field in ENUMS:return value if isinstance(value,str) and value in ENUMS[field] else None
    if field=='message':return value if isinstance(value,str) and value in EVENTS else None
    if field=='timestamp':return safe_date(value)
    if field=='operation_release':
        return value if isinstance(value,str) and re.fullmatch(r'[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}(?:-(?:alpha|beta|rc)\.[0-9]{1,4})?',value) else None
    n=number(value)
    if n is None or not -1<=n<=2**53:return None
    if field.startswith('issue_') and not -1<=n<=20:return None
    if field in ('generation_elapsed_ms','review_elapsed_ms') and not -1<=n<=86400000:return None
    if field.endswith('_attempted_calls') and not 0<=n<=1000:return None
    return n

def safe_observation(value):
    if not isinstance(value,dict):value={}
    warnings=value.get('warnings',[])
    if not isinstance(warnings,list):warnings=['invalid_observation_metadata']
    normalized=sorted({w if isinstance(w,str) and w in WARNING_CODES else 'invalid_observation_metadata' for w in warnings})
    result=dict(complete=value.get('complete') is True,
        start=safe_date(value.get('start')),end=safe_date(value.get('end')),
        environment=value.get('environment') if value.get('environment') in ('production','staging') else None,
        pages=number(value.get('pages')),column_batches=number(value.get('column_batches')),warnings=normalized,
        scope='Provider ingestion window; original observations can precede it.')
    if result['pages'] is not None and not 0<=result['pages']<=100:result['pages']=None
    if result['column_batches'] is not None and not 0<=result['column_batches']<=100:result['column_batches']=None
    return result

def normalize(row):
    """Accept selected Explore columns or genuine Sentry log items (never retain content fields)."""
    attributes=row.get('attributes',row)
    if not isinstance(attributes,dict):return {}
    result={}
    for field in FIELDS:
        value=attributes.get(field)
        if value is None:
            for kind in ('number','boolean','string'):
                keys=[prefix+'['+field+','+space+kind+']' for prefix in ('tag','tags') for space in ('',' ')]
                key=next((k for k in keys if k in attributes),None)
                if key is not None:
                    value=attributes[key];break
        if isinstance(value,dict):value=value.get('value')
        if value is not None and isinstance(value,(str,int,float,bool)):
            clean=safe_value(field,value)
            if clean is not None:result[field]=clean
    # Sentry log item body is an allowlisted event name, never general log text.
    name=row.get('message',row.get('body',result.get('message')))
    if isinstance(name,str) and name in EVENTS:
        result['message']=name
    else:result.pop('message',None)
    return result

def investigate(export,reference):
    if not isinstance(export,dict):raise ValueError('Expected an investigation export object')
    if not random_id(reference):raise ValueError('Reference must be a canonical random UUID')
    gaps=set()
    observation=safe_observation(export.get('observation',{}))
    if observation.get('complete') is not True:gaps.add('incomplete_export')
    gaps.update(observation.get('warnings',[]))
    records=[normalize(r) for r in export.get('records',[]) if isinstance(r,dict)]
    records=[r for r in records if r.get('message') and r.get('test_traffic') not in (True,'true')]
    def causal(r):
        return version(r,'correlation_version',1)
    matches=[r for r in records if causal(r) and any(r.get(k)==reference for k in IDS)]
    installations={r.get('installation_id') for r in matches if random_id(r.get('installation_id'))}
    if len(installations)>1:raise ValueError('Ambiguous reference across installations')
    installation=next(iter(installations),None)
    if not matches:gaps.add('reference_not_observed_with_supported_correlation')
    records=[r for r in records if installation is not None and r.get('installation_id')==installation]
    known={k:set() for k in LINK_IDS}
    selected=[]
    # Seed only explicit typed correlation. Never join by timestamp or legacy attempt_id.
    selected=[r for r in records if causal(r) and any(r.get(k)==reference for k in IDS)]
    changed=True
    while changed:
        before=len(selected)
        for r in selected:
            for k in LINK_IDS:
                if random_id(r.get(k)):known[k].add(r[k])
            if random_id(r.get('previous_attempt_id')):
                known['generation_attempt_id'].add(r['previous_attempt_id'])
        for r in records:
            if r in selected or not causal(r):continue
            if any(r.get(k) in known[k] for k in LINK_IDS) or r.get('previous_attempt_id') in known['generation_attempt_id']:
                selected.append(r)
        changed=len(selected)!=before
    # A transcript operation is linked only by the producer's explicit dependency event.
    transcript_ops={r.get('transcript_revision_id',r.get('transcript_id')) for r in selected
                    if r.get('message') in ('capture.transcript_link','transcription.dependency')
                    and random_id(r.get('transcript_revision_id',r.get('transcript_id')))}
    for r in records:
        if r not in selected and (r.get('feature')=='transcription' or r['message'].startswith('transcription.')) and r.get('operation_id') in transcript_ops:
            selected.append(r)
    health=[r for r in records if r.get('message')=='diagnostics.health']
    seen={};conflicts=set();duplicates=0
    for r in selected+health:
        stable=r.get('summary_id') or r.get('event_id')
        if not random_id(stable):
            gaps.add('missing_stable_event_id');continue
        key=(r['message'],stable)
        if key in seen:
            duplicates+=1
            # Re-enveloping can change emission and event identity, not the original summary evidence.
            ignored={'emitted_at_ms','event_id','timestamp'}
            if {k:v for k,v in seen[key].items() if k not in ignored}!={k:v for k,v in r.items() if k not in ignored}:
                conflicts.add(key);gaps.add('conflicting_duplicate')
            elif (number(r.get('emitted_at_ms')) or 10**30)<(number(seen[key].get('emitted_at_ms')) or 10**30):
                seen[key]=r
        else:seen[key]=r
    unique=[r for k,r in seen.items() if k not in conflicts]
    unique.sort(key=lambda r:(number(r.get('observed_at_ms')) is None,number(r.get('observed_at_ms')) or 0,r.get('summary_id',r.get('event_id',''))))
    timeline=[];health_result=[];attempts={};publications=[];captures=[]
    for r in unique:
        name=r['message'];unavailable=[]
        if not version(r,'schema_version',1):gaps.add('unsupported_envelope_version')
        fields=set(COMMON+IDS+FLAGS)
        if name=='distillation.attempt_summary':
            if version(r,'schema_version',1) and version(r,'outcome_version',1):fields.update(STAGE)
            else:gaps.add('unsupported_outcome_version');unavailable.extend(STAGE)
        if name=='capture.context_summary':
            if version(r,'schema_version',1) and version(r,'context_schema_version',1):fields.update(CONTEXT)
            else:gaps.add('unsupported_context_version');unavailable.extend(CONTEXT)
        if name=='diagnostics.health':
            fields=set(HEALTH)
            if not version(r,'budget_policy_version',2):gaps.add('unsupported_budget_policy_version')
            else:
                if any((number(r.get(k)) or 0)>0 for k in HEALTH if k.endswith('_dropped')):
                    gaps.add('budget_admission_suppression_observed')
                if any(number(r.get(lane+'_used')) is not None and number(r.get(lane+'_limit')) is not None
                       and number(r[lane+'_used'])>=number(r[lane+'_limit']) for lane in ('routine','critical','health','errors')):
                    gaps.add('budget_exhaustion_observed')
            if (number(r.get('dropped_reports')) or 0)>0:gaps.add('collection_warnings_observed')
        if name!='diagnostics.health' and not causal(r):gaps.add('legacy_correlation_unavailable')
        if name!='diagnostics.health' and r.get('correlation_complete') not in (True,'true'):gaps.add('incomplete_causal_identity')
        if number(r.get('observed_at_ms')) is None:gaps.add('original_observation_time_unavailable')
        values={}
        for k in sorted(fields):
            v=r.get(k)
            unknown=v is None or v=='unknown' or (k.endswith(('_elapsed_ms','_count')) and number(v)==-1)
            if name=='capture.context_summary':
                if k in COUNTS and r.get('segments_measured') not in (True,'true'):unknown=True
                if k in ('missing_duration_ms','coverage_complete') and r.get('coverage_measured') not in (True,'true'):unknown=True
            if unknown:unavailable.append(k)
            else:values[k]=v
        item=dict(evidence='observed',event=name,id=r.get('summary_id',r.get('event_id')),
                  observed_at_ms=number(r.get('observed_at_ms')),emitted_at_ms=number(r.get('emitted_at_ms')),
                  fields=values,unavailable=sorted(set(unavailable)))
        observed=item['observed_at_ms'];emitted=item['emitted_at_ms']
        item['emission_delay_ms']=emitted-observed if observed is not None and emitted is not None and emitted>=observed else None
        # Server timestamp selects the export window; it never replaces the original observation.
        item['provider_timestamp']=r.get('timestamp')
        if name=='diagnostics.health':health_result.append(item);continue
        timeline.append(item)
        if (version(r,'schema_version',1) and name in ('capture.completed','capture.saved')) or (name=='operation.summary'
                and version(r,'schema_version',1) and r.get('feature')=='capture' and r.get('outcome')=='completed'):
            captures.append(item['id'])
        attempt=r.get('generation_attempt_id')
        if causal(r) and random_id(attempt):
            a=attempts.setdefault(attempt,dict(generation_attempt_id=attempt,execution_ids=[],evidence='observed'))
            if random_id(r.get('execution_id')) and r['execution_id'] not in a['execution_ids']:a['execution_ids'].append(r['execution_id'])
        if name=='distillation.attempt_summary' and version(r,'schema_version',1) and version(r,'outcome_version',1) and r.get('stage_outcome') in ('published','published_without_quote'):
            publications.append(item['id'])
        elif name=='operation.summary' and version(r,'schema_version',1) and r.get('feature')=='distillation' and r.get('outcome')=='completed':
            publications.append(item['id'])
    if not health_result:gaps.add('reporting_health_unavailable')
    if not captures:gaps.add('capture_acceptance_unavailable')
    if not any(r['event']=='capture.context_summary' for r in timeline):gaps.add('context_evidence_unavailable')
    if not any(r['event']=='distillation.attempt_summary' for r in timeline):gaps.add('stage_evidence_unavailable')
    if not publications:gaps.add('publication_not_observed')
    return dict(report_version=1,reference=reference,observation=observation,
                capture_acceptance=dict(state='observed_saved' if captures else 'unknown',
                    evidence='observed' if captures else 'unavailable',event_ids=captures,
                    scope='Durable save evidence; capture.started is only a start and capture.accepted is input intent.'),
                publication=dict(state='observed_published' if publications else 'unknown',
                    evidence='observed' if publications else 'unavailable',event_ids=publications,
                    scope='Publication observed somewhere in this history; not current saved state or latest retry status.'),
                timeline=timeline,attempts=list(attempts.values()),reporting_health=health_result,
                duplicates=duplicates,gaps=sorted(gaps),
                inferences=[dict(evidence='inferred',claim='Records share explicit producer causal identifiers; window boundaries may omit earlier/later attempts.')] if timeline else [],
                limits=LIMITS)

def main():
    from export_operations import private_write
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('export',type=Path);p.add_argument('--reference',required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    try:
        result=investigate(json.loads(a.export.read_text()),a.reference)
        private_write(a.output,result)
    except (ValueError,OSError,TypeError,KeyError):p.exit(2,'Report failed; check private input and destination. No diagnostics printed.\n')
    print('Saved private investigation report; observation gaps and semantic limits apply.')
if __name__=='__main__':main()
