"""Create/update only DistillPod dashboards from reviewed JSON definitions.

The personal token stays on the operator's computer; never put it in an APK.
Use --apply after reviewing --dry-run output. Existing unrelated dashboards are untouched.
"""
import argparse
import json
from pathlib import Path
import urllib.request
import urllib.error


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--token-file',type=Path,required=True)
    p.add_argument('--organization',required=True)
    p.add_argument('--project-id',type=int,required=True)
    p.add_argument('--apply',action='store_true')
    args=p.parse_args()
    token=args.token_file.read_text().strip()
    base='https://us.sentry.io/api/0/organizations/'+args.organization+'/dashboards/'
    def request(url,body=None,method=None):
        req=urllib.request.Request(url,data=json.dumps(body).encode() if body is not None else None,method=method,
            headers={'Authorization':'Bearer '+token,'Content-Type':'application/json','User-Agent':'DistillPodReliability/1.0'})
        try:
            with urllib.request.urlopen(req,timeout=30) as r:return json.load(r)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Dashboard API {error.code}: {error.read().decode()[:1000]}") from None
    existing={d['title']:d['id'] for d in request(base)}
    for f in sorted(Path(__file__).parent.glob('*.json')):
        body=json.loads(f.read_text());body['projects']=[args.project_id]
        name=body['title'];dashboard=existing.get(name)
        if args.apply:
            result=request(base+(str(dashboard)+'/' if dashboard else ''),body,'PUT' if dashboard else 'POST')
            # Read-back verifies the deployed widget definitions are retrievable.
            check=request(base+str(result['id'])+'/')
            assert len(check['widgets'])==len(body['widgets'])
            print(name+': https://'+args.organization+'.sentry.io/dashboard/'+str(result['id'])+'/')
        else:print(('UPDATE ' if dashboard else 'CREATE ')+name+' ('+str(len(body['widgets']))+' widgets)')

if __name__=='__main__':main()
