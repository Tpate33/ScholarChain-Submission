#!/usr/bin/env python3
"""Pure canned fixtures: no AWS SDK import, provider calls, state or saved-plan reads."""
import datetime as dt
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]

def module(name):
    spec = importlib.util.spec_from_file_location(name,ROOT/'scripts/runtime'/f'{name}.py')
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded

approval = module('approval')
monitor = module('deadline_monitor')
control = module('control')
NOW = dt.datetime(2026,11,24,12,tzinfo=dt.timezone.utc)

class OfflineChecks(unittest.TestCase):
    def test_monitor_zip_only_reviewed_source(self):
        import zipfile
        with zipfile.ZipFile(ROOT/'infra/terraform/environments/dev/runtime/deadline-monitor.zip') as archive:
            self.assertEqual(archive.namelist(),['deadline_monitor.py'])
            self.assertEqual(archive.read('deadline_monitor.py'),(ROOT/'scripts/runtime/deadline_monitor.py').read_bytes())
    def test_storage_contract_matches_application(self):
        import re
        storage=(ROOT/'infra/terraform/environments/dev/runtime/storage.tf').read_text()
        production=(ROOT/'modules/storage/src/main/scala/scholarchain/storage/model/TableSpecs.scala').read_text()
        roles=re.findall(r'case (\w+) extends TableRole',production)
        actual=dict((k,re.findall(r'"([a-zA-Z0-9]+)"',v)) for k,v in re.findall(r'"(\w+)"\s*=\s*\[([^]]*)\]',storage))
        self.assertEqual(set(actual),set(roles))
        self.assertEqual(sum(map(len,actual.values())),8)
        for role in roles:
            section=production.split('val '+role+': TableSpec = TableSpec(',1)[1].split('\n  val ',1)[0]
            expected=re.findall(r'GlobalIndex\("(\w+)"',section)
            self.assertEqual(actual[role],expected)
        self.assertIn('dimension          = 256',storage)
        self.assertIn('distance_metric    = "cosine"',storage)
    def package(self):
        return {'schemaVersion':1,'runId':'fixture-only','start':'2026-11-24T11:00:00Z',
                'runtimeDeadline':'2026-11-24T13:00:00Z','neptuneDeadline':'2026-11-24T14:00:00Z',
                'graphExperimentDeadline':'2026-11-24T12:20:00Z','endpointDeadline':'2026-11-24T17:00:00Z','emrDeadline':'2026-11-24T13:00:00Z',
                'verifiedPriorSpendUsd':'0.55','incrementalEnvelopeUsd':'12.00',
                'totalVectorCount':10000,'totalEmbeddingCalls':10500,'totalAnswerCalls':100,
                'graphRequestCap':50000,'artifactSha256':{'fixture-only':'a'*64},
                'account':'123456789012','region':'us-east-1',
                **{k:True for k in ['inventoryReviewed','quotasReviewed','modelsReviewed',
                   'censusReviewed','cleanupReviewed','secretsPopulated','monitorTested']}}
    def test_approval_valid_fixture(self):
        approval.validate(self.package(),NOW)
    def test_approval_refuses_caps_gates_windows_spend(self):
        for field,value in [('totalVectorCount',10001),('totalEmbeddingCalls',10501),('totalAnswerCalls',101),
                            ('graphRequestCap',50001),('verifiedPriorSpendUsd','13.01'),('verifiedPriorSpendUsd','NaN'),
                            ('modelsReviewed',False),('runtimeDeadline','2026-11-24T14:00:00Z'),
                            ('neptuneDeadline','2026-11-27T00:00:00Z')]:
            with self.subTest(field=field,value=value):
                data=self.package();data[field]=value
                with self.assertRaises((ValueError,ArithmeticError)):
                    approval.validate(data,NOW)
    def test_approval_expired(self):
        with self.assertRaises(ValueError):
            approval.validate(self.package(),NOW+dt.timedelta(hours=2))
    def task(self,kind,age):
        return {'taskDefinitionArn':'arn:aws:ecs:us-east-1:123456789012:task-definition/bounded-'+kind+':1',
                'taskArn':'arn:fixture:'+kind,'createdAt':NOW-dt.timedelta(seconds=age)}
    def config(self):
        return {'cluster':'fixture-cluster','services':['q','e','g'],
                'deadline':'2026-11-24T13:00:00Z','topic':'fixture-topic','emr_cluster_id':'',
                'families':{'bounded-'+k:k for k in monitor.LIMITS},
                'graph_resource':'service/fixture/g','scaling':Mock()}
    def test_all_task_caps(self):
        for kind,cap in monitor.LIMITS.items():
            self.assertFalse(monitor.expired(self.task(kind,cap-1),NOW.timestamp(),NOW.timestamp()+500,self.config()['families']))
            self.assertTrue(monitor.expired(self.task(kind,cap),NOW.timestamp(),NOW.timestamp()+500,self.config()['families']))
    def test_no_expiry_no_stop(self):
        ecs=Mock();ecs.list_tasks.return_value={'taskArns':['x']};ecs.describe_tasks.return_value={'tasks':[self.task('graph',100)]}
        monitor.enforce(ecs,Mock(),Mock(),self.config(),NOW.timestamp())
        ecs.stop_task.assert_not_called();ecs.update_service.assert_not_called()
    def test_worker_serialization_stops_both(self):
        ecs=Mock();ecs.list_tasks.return_value={'taskArns':['x','y']};ecs.describe_tasks.return_value={'tasks':[self.task('er',1),self.task('loader',1)]}
        monitor.enforce(ecs,Mock(),Mock(),self.config(),NOW.timestamp())
        self.assertEqual(ecs.stop_task.call_count,2)
    def test_runtime_deadline_zeroes_three_services(self):
        ecs=Mock();ecs.list_tasks.return_value={'taskArns':[]}
        cfg=self.config();monitor.enforce(ecs,Mock(),Mock(),cfg,NOW.timestamp()+3600)
        self.assertEqual(ecs.update_service.call_count,3)
        cfg['scaling'].register_scalable_target.assert_called_once()
    def test_manual_stop_suspends_before_zero(self):
        aws=Mock()
        control.stop_services(aws,{'services':{'query':'q','evidence':'e','graph':'g'},'cluster':'fixture-cluster'})
        calls=aws.call_args_list
        self.assertEqual(calls[0].args[:2],('application-autoscaling','register-scalable-target'))
        self.assertEqual(calls[0].args[calls[0].args.index('--min-capacity')+1],'0')
        import json
        self.assertTrue(all(json.loads(calls[0].args[-1]).values()))
        self.assertEqual(len(calls),4)
        for call in calls[1:]:
            self.assertEqual(call.args[:2],('ecs','update-service'))
            self.assertEqual(call.args[-2:],('--desired-count','0'))
    def test_monitor_after_graph_deadline_never_restarts_stopped_service(self):
        ecs=Mock();ecs.list_tasks.return_value={'taskArns':[]}
        cfg=self.config();cfg.update(graph_deadline='2026-11-24T11:59:00Z',graph_service='g')
        for delay in (0,60,120):
            monitor.enforce(ecs,Mock(),Mock(),cfg,NOW.timestamp()+delay)
        ecs.update_service.assert_not_called()
        self.assertEqual(cfg['scaling'].register_scalable_target.call_count,3)
        for call in cfg['scaling'].register_scalable_target.call_args_list:
            self.assertEqual(call.kwargs['MinCapacity'],0)
            self.assertEqual(call.kwargs['MaxCapacity'],1)
            self.assertTrue(all(call.kwargs['SuspendedState'].values()))
    def test_inspection_failure_propagates(self):
        ecs=Mock();ecs.list_tasks.side_effect=RuntimeError('fixture failure')
        with self.assertRaises(RuntimeError):
            monitor.enforce(ecs,Mock(),Mock(),self.config(),NOW.timestamp())
        self.assertEqual(ecs.list_tasks.call_count,1)
    def test_emr_first_failed_step_terminates(self):
        ecs=Mock();ecs.list_tasks.return_value={'taskArns':[]};emr=Mock()
        emr.describe_cluster.return_value={'Cluster':{'Status':{'State':'RUNNING','Timeline':{'CreationDateTime':NOW}}}}
        emr.list_steps.return_value={'Steps':[{'Status':{'State':'FAILED'}}]}
        cfg=self.config();cfg.update(emr_cluster_id='j-FIXTURE',emr_deadline='2026-11-24T13:00:00Z')
        monitor.enforce(ecs,emr,Mock(),cfg,NOW.timestamp())
        emr.terminate_job_flows.assert_called_once_with(JobFlowIds=['j-FIXTURE'])
    def test_emr_120min_cap(self):
        ecs=Mock();ecs.list_tasks.return_value={'taskArns':[]};emr=Mock()
        emr.describe_cluster.return_value={'Cluster':{'Status':{'State':'RUNNING','Timeline':{'CreationDateTime':NOW-dt.timedelta(hours=2)}}}}
        emr.list_steps.return_value={'Steps':[]}
        cfg=self.config();cfg.update(emr_cluster_id='j-FIXTURE',emr_deadline='2026-11-24T13:00:00Z')
        monitor.enforce(ecs,emr,Mock(),cfg,NOW.timestamp())
        emr.terminate_job_flows.assert_called_once()

if __name__ == '__main__':unittest.main(verbosity=2)
