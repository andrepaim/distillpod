"""Summarize exported operation.summary attributes without double-counting delivery retries.

Input: JSON array of attributes (not full Sentry events). Reports are limited to the
observed query window/consenting devices; they cannot establish unobserved starts.
"""
import argparse
import json
from collections import defaultdict


def summarize(records):
    summaries={}
    for r in records:
        if r.get('test_traffic') in (True,'true') or r.get('schema_version') not in (1,'1'):
            continue
        if r.get('summary_id'):
            summaries[r['summary_id']]=r
    groups=defaultdict(list)
    for r in summaries.values():
        groups[(r['installation_id'],r['feature'],r['operation_id'])].append(r)
    features=defaultdict(lambda: {'observed_jobs':0,'outcomes':defaultdict(int),'observed_attempts':0,'queued_only_attempt_ids':0,'unknown_only_attempt_ids':0,'recovered_jobs':0,'resolved_success_rate':None})
    for (_,feature,_),rows in groups.items():
        latest=max(rows,key=lambda r:int(r['sequence']))
        f=features[feature];f['observed_jobs']+=1;f['outcomes'][latest['outcome']]+=1
        attempts=defaultdict(set)
        for r in rows:attempts[r['attempt_id']].add(r['outcome'])
        for states in attempts.values():
            if states & {'running','partial','completed','failed','cancelled','interrupted'}:f['observed_attempts']+=1
            elif states <= {'queued','waiting','paused'}:f['queued_only_attempt_ids']+=1
            else:f['unknown_only_attempt_ids']+=1
        if latest['outcome']=='completed' and any(r['outcome'] in ('failed','interrupted') for r in rows):f['recovered_jobs']+=1
    for f in features.values():
        n=f['outcomes'].get('completed',0)+f['outcomes'].get('failed',0)
        if n:f['resolved_success_rate']=f['outcomes'].get('completed',0)/n
    return {'scope':'Observed reporting installations and query window only; outstanding/unknown work is excluded from resolved success rate. Missing reports remain unobservable.', 'features':dict(features)}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('export');args=p.parse_args()
    with open(args.export,encoding='utf8') as f:rows=json.load(f)
    print(json.dumps(summarize(rows),indent=2))
