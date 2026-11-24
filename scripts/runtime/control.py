#!/usr/bin/env python3
"""Human-operated single-attempt runtime admission. Default prints an offline action.
No EMR submission, deployment, deletion or automatic recovery is performed here.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from approval import validate, stamp


def load(path):
    if path.is_symlink() or path.suffix != '.json' or path.name.startswith('.'):
        raise ValueError('regular nonsecret JSON required')
    raw=path.read_bytes()
    return json.loads(raw),hashlib.sha256(raw).hexdigest()


def check(cfg):
    if set(cfg) != {'schemaVersion','account','runId','cluster','subnet','securityGroup','tasks','services'} or cfg['schemaVersion'] != 1:
        raise ValueError('invalid control inventory')
    account=cfg['account'];prefix='scholarchain-dev-bounded'
    if not re.fullmatch(r'[0-9]{12}',account) or not re.fullmatch(r'[a-z0-9-]{1,48}',cfg['runId']):
        raise ValueError('invalid account/run')
    if cfg['cluster'] != 'arn:aws:ecs:us-east-1:'+account+':cluster/'+prefix:
        raise ValueError('wrong bounded cluster')
    if not re.fullmatch(r'subnet-[0-9a-f]{8,17}',cfg['subnet']) or not re.fullmatch(r'sg-[0-9a-f]{8,17}',cfg['securityGroup']):
        raise ValueError('invalid network inventory')
    if set(cfg['tasks']) != {'er','loader','collector'} or set(cfg['services']) != {'query','evidence','graph'}:
        raise ValueError('incomplete tasks/services')
    for k,arn in cfg['tasks'].items():
        if not re.fullmatch('arn:aws:ecs:us-east-1:'+account+':task-definition/'+prefix+'-'+k+r':[1-9][0-9]*',arn):
            raise ValueError('unapproved task definition')
    for k,name in cfg['services'].items():
        if name != prefix+'-'+k:raise ValueError('wrong service')
    return cfg


def stop_services(aws, cfg):
    # Suspend every scaling path before stopping. No future monitor tick raises zero.
    resource='service/scholarchain-dev-bounded/'+cfg['services']['graph']
    aws('application-autoscaling','register-scalable-target','--service-namespace','ecs',
        '--resource-id',resource,'--scalable-dimension','ecs:service:DesiredCount',
        '--min-capacity','0','--max-capacity','2','--suspended-state',
        json.dumps({'DynamicScalingInSuspended':True,'DynamicScalingOutSuspended':True,'ScheduledScalingSuspended':True}))
    for name in cfg['services'].values():
        aws('ecs','update-service','--cluster',cfg['cluster'],'--service',name,'--desired-count','0')


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('inventory',type=Path)
    parser.add_argument('action',choices=['er','loader','start-services','collector','start-graph-experiment','stop-services'])
    parser.add_argument('--execute',action='store_true')
    args=parser.parse_args()
    cfg,cfgsha=load(args.inventory);check(cfg)
    approval_path=Path('infra/terraform/environments/dev/runtime/approved-run.json')
    package,package_sha=load(approval_path)
    validate(package,dt.datetime.now(dt.timezone.utc),allow_expired=args.action == 'stop-services')
    if cfg['account'] != package['account'] or cfg['runId'] != package['runId']:
        raise ValueError('approval/inventory mismatch')
    if not args.execute:
        print('OFFLINE action='+args.action+' inventory_sha256='+cfgsha+' approval_sha256='+package_sha)
        return
    if os.environ.get('CLAUDECODE') or not sys.stdin.isatty() or not sys.stdout.isatty():
        raise ValueError('human terminal required')
    head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    dirty=subprocess.check_output(['git','status','--porcelain','--untracked-files=all'],text=True)
    if dirty:raise ValueError('clean committed tree required')
    token=head+':'+cfgsha+':'+package_sha+':'+args.action
    if os.environ.get('SCHOLARCHAIN_RUN_APPROVAL') != token:
        raise ValueError('exact HEAD/inventory/approval/action token required')
    profile=os.environ.get('AWS_PROFILE','')
    if not re.fullmatch(r'[a-zA-Z0-9_-]+',profile):raise ValueError('explicit approved AWS_PROFILE required')
    env=dict(os.environ,AWS_MAX_ATTEMPTS='1',AWS_PAGER='',AWS_RETRY_MODE='standard')
    def aws(*argv):
        result=subprocess.run(['aws','--profile',profile,'--region','us-east-1','--output','json',*argv],
                              env=env,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,check=True,timeout=45)
        return json.loads(result.stdout)
    if aws('sts','get-caller-identity')['Account'] != cfg['account']:
        raise ValueError('wrong AWS account')
    # One exclusive local receipt makes each stage single-attempt. Preserve failures for review.
    receipts=Path('target/runtime-control')/cfg['runId'];receipts.mkdir(parents=True,exist_ok=True)
    dependencies={'loader':'er','start-services':'loader','collector':'start-services','start-graph-experiment':'start-services'}
    if args.action in dependencies:
        previous=receipts/(dependencies[args.action]+'.json')
        if not previous.is_file() or json.loads(previous.read_text()).get('status') != 'SUCCEEDED':
            raise ValueError('previous stage did not succeed')
    receipt=receipts/(args.action+'.json')
    with receipt.open('x') as stream:json.dump({'action':args.action,'head':head,'inventorySha256':cfgsha,'status':'ADMITTED'},stream)
    if args.action == 'start-graph-experiment':
        end=stamp(package['graphExperimentDeadline']).timestamp()
        if not 0 < end-time.time() <= 1200:raise ValueError('graph experiment must fit 20 minutes')
        resource='service/scholarchain-dev-bounded/'+cfg['services']['graph']
        aws('application-autoscaling','register-scalable-target','--service-namespace','ecs',
            '--resource-id',resource,'--scalable-dimension','ecs:service:DesiredCount',
            '--min-capacity','1','--max-capacity','2','--suspended-state',
            json.dumps({'DynamicScalingInSuspended':False,'DynamicScalingOutSuspended':False,'ScheduledScalingSuspended':True}))
        receipt.write_text(json.dumps({'action':args.action,'status':'SUCCEEDED','head':head}))
        print('CONTROL graph experiment admitted; external deadline guard closes window')
        return
    if args.action in ('start-services','stop-services'):
        count='1' if args.action == 'start-services' else '0'
        if args.action == 'stop-services':
            stop_services(aws,cfg)
        else:
            for name in cfg['services'].values():
                aws('ecs','update-service','--cluster',cfg['cluster'],'--service',name,'--desired-count',count)
        receipt.write_text(json.dumps({'action':args.action,'status':'SUCCEEDED','head':head}))
        print('CONTROL services desired='+count)
        return
    active=aws('ecs','list-tasks','--cluster',cfg['cluster'],'--max-results','100')
    if active.get('nextToken'):raise ValueError('inventory exceeds bounds')
    arns=active.get('taskArns',[])
    if arns:
        inspected=aws('ecs','describe-tasks','--cluster',cfg['cluster'],'--tasks',*arns)
        if inspected.get('failures'):raise ValueError('failed inspection')
        if any(t['taskDefinitionArn'] in cfg['tasks'].values() for t in inspected['tasks']):
            raise ValueError('another bounded worker/collector is active')
    network={'awsvpcConfiguration':{'subnets':[cfg['subnet']],'securityGroups':[cfg['securityGroup']],'assignPublicIp':'DISABLED'}}
    result=aws('ecs','run-task','--cluster',cfg['cluster'],'--task-definition',cfg['tasks'][args.action],
               '--launch-type','FARGATE','--count','1','--client-token',cfg['runId']+'-'+args.action,
               '--network-configuration',json.dumps(network),'--started-by',cfg['runId'])
    if result.get('failures') or len(result.get('tasks',[])) != 1:raise ValueError('task admission failed')
    task=result['tasks'][0]['taskArn']
    seconds={'er':1200,'loader':2700,'collector':1200}[args.action]
    end=min(time.time()+seconds,stamp(package['runtimeDeadline']).timestamp())
    while time.time() < end:
        result=aws('ecs','describe-tasks','--cluster',cfg['cluster'],'--tasks',task)
        if result.get('failures') or len(result.get('tasks',[])) != 1:raise ValueError('task inspection failed')
        current=result['tasks'][0]
        if current['lastStatus'] == 'STOPPED':
            exits=[c.get('exitCode') for c in current.get('containers',[])]
            if not exits or any(c != 0 for c in exits):raise ValueError('task failed; no retry authorized')
            receipt.write_text(json.dumps({'action':args.action,'status':'SUCCEEDED','taskArn':task,'head':head}))
            print('CONTROL task succeeded action='+args.action)
            return
        time.sleep(5)
    aws('ecs','stop-task','--cluster',cfg['cluster'],'--task',task,'--reason','Bounded task deadline')
    raise ValueError('task deadline reached; no retry authorized')

if __name__ == '__main__':
    try:main()
    except (ValueError,KeyError,TypeError,OSError,subprocess.SubprocessError) as error:
        sys.exit('REFUSED control: '+type(error).__name__)
