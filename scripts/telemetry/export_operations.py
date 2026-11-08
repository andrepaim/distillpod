"""Read a bounded, paginated window of operation summaries for reliability_report.py.

Uses the official Explore table API. This is a personal-alpha diagnostic report,
not a complete provider archive. Legacy failures abort output; investigation mode
writes an explicitly incomplete private artifact and exits 2 on partial reads.
"""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.parse
import urllib.request

NUMBERS={'sequence','schema_version','elapsed_ms','operation_started_ms','observed_at_ms'}
FIELDS=['summary_id','installation_id','feature','operation_id','attempt_id','sequence','outcome','reason','source','schema_version','test_traffic','elapsed_ms','operation_started_ms','observed_at_ms','operation_release','timestamp']


def private_write(path, value):
    """Atomic owner-only output; refuse symlink destinations (including existing reports)."""
    import tempfile
    path=Path(path)
    if path.is_symlink():raise ValueError('Symlink output refused')
    path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    fd,name=tempfile.mkstemp(prefix='.distillpod-report-',dir=path.parent)
    try:
        os.fchmod(fd,0o600)
        with os.fdopen(fd,'w',encoding='utf8') as f:
            json.dump(value,f,indent=2,allow_nan=False);f.write('\n')
            f.flush();os.fsync(f.fileno())
        os.replace(name,path)
    finally:
        if os.path.exists(name):os.unlink(name)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        return None

def _open_readonly(request,timeout):
    # Refuse before sending credentials to any redirected endpoint.
    return urllib.request.build_opener(_NoRedirect()).open(request,timeout=timeout)

def collect_window(token, organization, project_id, environment, start, end,
                   max_rows=10000,max_pages=100):
    """Collect only selected content-free fields; partial reads remain explicitly incomplete."""
    from datetime import datetime
    from investigation_report import FIELDS as INVESTIGATION_FIELDS, normalize
    if not re.fullmatch('[a-zA-Z0-9_-]+',organization):raise ValueError('Invalid organization')
    if environment not in ('production','staging') or project_id<=0:raise ValueError('Invalid scope')
    try:
        a=datetime.fromisoformat(start.replace('Z','+00:00'));b=datetime.fromisoformat(end.replace('Z','+00:00'))
        seconds=(b-a).total_seconds()
        if a.utcoffset() is None or b.utcoffset() is None or not 0<seconds<=7*86400:raise ValueError()
    except (ValueError,TypeError):raise ValueError('Use explicit timezone-aware start/end, at most seven days') from None
    if not 1<=max_rows<=10000 or not 1<=max_pages<=100:raise ValueError('Invalid export cap')
    booleans={'test_traffic','correlation_complete','context_expanded','transcript_changed','model_changed',
              'prompt_changed','candidate_reused','passage_complete','takeaway_supported','relevant',
              'quote_faithful','needs_context','contradictory_review','quote_omitted','expanded_context',
              'duration_known','coverage_measured','coverage_complete','segments_measured','frozen_context'}
    numeric=set(NUMBERS)|{'correlation_version','outcome_version','context_schema_version','emitted_at_ms',
        'prompt_version','context_text_units','budget_policy_version','budget_day','daily_limit',
        'pending_envelopes','dropped_reports'}
    numeric.update(f for f in INVESTIGATION_FIELDS if f.endswith(('_ms','_count','_calls','_used','_limit','_dropped')))
    def column(f):
        return 'tags['+f+',number]' if f in numeric else 'tags['+f+',boolean]' if f in booleans else f
    params=[('dataset','logs'),('project',str(project_id)),('environment',environment),
        ('start',start),('end',end),('per_page','100'),('sort','timestamp'),
        ('query','(message:capture.* OR message:transcription.* OR message:distillation.* OR message:operation.summary OR message:diagnostics.health) !tags[test_traffic,boolean]:true')]
    params += [('field',column(f)) for f in INVESTIGATION_FIELDS]
    endpoint='https://us.sentry.io/api/0/organizations/'+organization+'/events/'
    initial_url=endpoint+'?'+urllib.parse.urlencode(params)
    url=initial_url
    observation=dict(start=start,end=end,environment=environment,complete=False,
        pages=0,warnings=[],scope='Provider ingestion window; original observations can precede it.')
    result=dict(export_version=1,observation=observation,records=[])
    seen=set()
    def stop(reason):
        observation['warnings'].append(reason);return result
    while url:
        parsed=urllib.parse.urlsplit(url)
        if parsed.scheme!='https' or parsed.netloc!='us.sentry.io' or parsed.path!=urllib.parse.urlsplit(endpoint).path or parsed.fragment:
            return stop('unsafe_pagination')
        if url in seen:return stop('repeated_cursor')
        if observation['pages']>=max_pages:return stop('page_cap')
        seen.add(url)
        try:
            request=urllib.request.Request(url,headers={'Authorization':'Bearer '+token,
                                                       'User-Agent':'DistillPodInvestigation/1.0'})
            with _open_readonly(request,timeout=30) as response:
                # Do not follow redirects carrying credentials to another endpoint.
                final=response.geturl() if hasattr(response,'geturl') else url
                if urllib.parse.urlsplit(final)[:3]!=parsed[:3]:return stop('unexpected_response_url')
                payload=json.load(response);links=response.headers.get('Link','')
        except Exception:
            return stop('api_read_failed')  # Never serialize exception text, token or request URL.
        observation['pages']+=1
        if not isinstance(payload,dict) or not isinstance(payload.get('data'),list):
            return stop('invalid_api_response')
        if any(payload.get(k) for k in ('error','errors','partial','isPartial','is_partial','truncated')):
            return stop('partial_api_response')
        if any(not isinstance(r,dict) for r in payload['data']):return stop('invalid_api_row')
        remaining=max_rows-len(result['records'])
        result['records'].extend(normalize(r) for r in payload['data'][:remaining])
        if len(payload['data'])>remaining:return stop('row_cap')
        next_parts=[p for p in links.split(',') if re.search(r'rel="?next"?',p)]
        if len(next_parts)>1:return stop('invalid_pagination')
        url=None
        if next_parts:
            part=next_parts[0]
            if 'results="true"' in part:
                match=re.search(r'<([^>]+)>',part)
                if not match:return stop('invalid_pagination')
                candidate=urllib.parse.urlsplit(match.group(1))
                if candidate.scheme!='https' or candidate.netloc!='us.sentry.io' or candidate.path!=urllib.parse.urlsplit(endpoint).path or candidate.fragment:
                    return stop('unsafe_pagination')
                query=urllib.parse.parse_qs(candidate.query,keep_blank_values=True)
                original=urllib.parse.parse_qs(urllib.parse.urlsplit(initial_url).query,keep_blank_values=True)
                if any(k!='cursor' and (k not in original or v!=original[k]) for k,v in query.items()):
                    return stop('changed_pagination_scope')
                if len(query.get('cursor',[]))!=1 or not query['cursor'][0]:
                    return stop('missing_pagination_cursor')
                url=initial_url+'&'+urllib.parse.urlencode({'cursor':query['cursor'][0]})
            elif 'results="false"' not in part:return stop('invalid_pagination')
        if len(result['records'])>=max_rows:return stop('row_cap')
        if len(payload['data'])>=100 and not next_parts:return stop('pagination_metadata_missing')
    observation['complete']=True
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--token-file',type=Path,required=True)
    p.add_argument('--organization',required=True)
    p.add_argument('--project-id',type=int,required=True)
    p.add_argument('--environment',default='production',choices=['production','staging'])
    p.add_argument('--period',default='7d')
    p.add_argument('--investigation',action='store_true')
    p.add_argument('--start')
    p.add_argument('--end')
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    if not re.fullmatch('[a-zA-Z0-9_-]+',args.organization):p.error('Invalid organization slug')
    if not re.fullmatch(r'\d+[mhd]',args.period):p.error('Invalid reporting period')
    token=args.token_file.read_text().strip()
    if args.investigation:
        if not args.start or not args.end:p.error('Investigation requires --start and --end')
        try:
            result=collect_window(token,args.organization,args.project_id,args.environment,args.start,args.end)
            private_write(args.output,result)
        except (ValueError,OSError):p.exit(2,'Export failed; check scope and private file access. No diagnostics printed.\n')
        print('Saved private investigation export; complete API read: '+str(result['observation']['complete']))
        if not result['observation']['complete']:raise SystemExit(2)
        return
    params=[('dataset','logs'),('project',str(args.project_id)),('environment',args.environment),('statsPeriod',args.period),('per_page','100'),('sort','timestamp'),('query','message:operation.summary tags[schema_version,number]:1 !tags[test_traffic,boolean]:true')]+[('field',f'tags[{f},number]' if f in NUMBERS else 'tags[test_traffic,boolean]' if f=='test_traffic' else f) for f in FIELDS]
    url='https://us.sentry.io/api/0/organizations/'+args.organization+'/events/?'+urllib.parse.urlencode(params)
    rows=[];seen=set()
    while url:
        if url in seen:raise RuntimeError('Repeated pagination cursor; no output written')
        if urllib.parse.urlparse(url).netloc!='us.sentry.io' or not url.startswith('https://'):raise RuntimeError('Unexpected pagination host')
        seen.add(url)
        request=urllib.request.Request(url,headers={'Authorization':'Bearer '+token,'User-Agent':'DistillPodReliabilityReport/1.0'})
        with _open_readonly(request,timeout=30) as response:
            result=json.load(response);links=response.headers.get('Link','')
        rows.extend({k:row.get(f'tags[{k},number]' if k in NUMBERS else 'tags[test_traffic,boolean]' if k=='test_traffic' else k) for k in FIELDS} for row in result['data'])
        next_link=next((part for part in links.split(',') if 'rel="next"' in part and 'results="true"' in part),None)
        url=re.search(r'<([^>]+)>',next_link).group(1) if next_link else None
        if url and len(rows)>=10000:raise RuntimeError('Window exceeds 10,000 reports; narrow the period. No output written')
    private_write(args.output,rows)
    print(f'Wrote {len(rows)} observed summary records; provider/window gaps remain possible.')

if __name__=='__main__':main()
