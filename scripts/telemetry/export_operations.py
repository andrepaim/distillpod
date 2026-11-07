"""Read a bounded, paginated window of operation summaries for reliability_report.py.

Uses the official Explore table API. This is a personal-alpha diagnostic report,
not a complete provider archive. Failure/truncation aborts before writing output.
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

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--token-file',type=Path,required=True)
    p.add_argument('--organization',required=True)
    p.add_argument('--project-id',type=int,required=True)
    p.add_argument('--environment',default='production',choices=['production','staging'])
    p.add_argument('--period',default='7d')
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    if not re.fullmatch('[a-zA-Z0-9_-]+',args.organization):p.error('Invalid organization slug')
    if not re.fullmatch(r'\d+[mhd]',args.period):p.error('Invalid reporting period')
    token=args.token_file.read_text().strip()
    params=[('dataset','logs'),('project',str(args.project_id)),('environment',args.environment),('statsPeriod',args.period),('per_page','100'),('sort','timestamp'),('query','message:operation.summary tags[schema_version,number]:1 !tags[test_traffic,boolean]:true')]+[('field',f'tags[{f},number]' if f in NUMBERS else 'tags[test_traffic,boolean]' if f=='test_traffic' else f) for f in FIELDS]
    url='https://us.sentry.io/api/0/organizations/'+args.organization+'/events/?'+urllib.parse.urlencode(params)
    rows=[];seen=set()
    while url:
        if url in seen:raise RuntimeError('Repeated pagination cursor; no output written')
        if urllib.parse.urlparse(url).netloc!='us.sentry.io' or not url.startswith('https://'):raise RuntimeError('Unexpected pagination host')
        seen.add(url)
        request=urllib.request.Request(url,headers={'Authorization':'Bearer '+token,'User-Agent':'DistillPodReliabilityReport/1.0'})
        with urllib.request.urlopen(request,timeout=30) as response:
            result=json.load(response);links=response.headers.get('Link','')
        rows.extend({k:row.get(f'tags[{k},number]' if k in NUMBERS else 'tags[test_traffic,boolean]' if k=='test_traffic' else k) for k in FIELDS} for row in result['data'])
        next_link=next((part for part in links.split(',') if 'rel="next"' in part and 'results="true"' in part),None)
        url=re.search(r'<([^>]+)>',next_link).group(1) if next_link else None
        if url and len(rows)>=10000:raise RuntimeError('Window exceeds 10,000 reports; narrow the period. No output written')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    fd=os.open(args.output,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w',encoding='utf8') as f:json.dump(rows,f)
    args.output.chmod(0o600)
    print(f'Wrote {len(rows)} observed summary records; provider/window gaps remain possible.')

if __name__=='__main__':main()
