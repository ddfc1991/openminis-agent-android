import importlib.util, json, tempfile, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def load(name,path):
 spec=importlib.util.spec_from_file_location(name,ROOT/path); mod=importlib.util.module_from_spec(spec); spec.loader.exec_module(mod); return mod
cg=load('mines_codegraph','tools/mines_codegraph.py')
td=load('mines_trace_diagnose','tools/mines_trace_diagnose.py')
class GraphTest(unittest.TestCase):
 def test_import_impact_and_no_network(self):
  with tempfile.TemporaryDirectory() as td0:
   root=Path(td0); (root/'a.kt').write_text('package demo.core\nclass CoreThing {}\n')
   (root/'b.kt').write_text('package demo.ui\nimport demo.core.CoreThing\nclass UserThing {}\n')
   g=cg.graph(root); impact=cg.impact(g,['CoreThing'])
   self.assertEqual(impact['targets'],['a.kt']); self.assertEqual(impact['direct_importers'],['b.kt'])
 def test_wildcard_import_resolves_package_and_does_not_leak_cross_package_edges(self):
  with tempfile.TemporaryDirectory() as td0:
   root=Path(td0); (root/'a.kt').write_text('package demo.core\nclass A')
   (root/'b.kt').write_text('package demo.core\nclass B')
   (root/'c.kt').write_text('package demo.ui\nimport demo.core.*\nclass C')
   g=cg.graph(root); ui=next(x for x in g['files'] if x['path']=='c.kt')
   self.assertEqual(ui['depends_on'],['a.kt','b.kt'])
 def test_no_unrelated_target_guess(self):
  with tempfile.TemporaryDirectory() as td0:
   root=Path(td0); (root/'a.kt').write_text('package demo\nclass A')
   self.assertEqual(cg.impact(cg.graph(root),['Absent'])['unresolved_targets'],['Absent'])
class SourceSafetyTest(unittest.TestCase):
 def test_diagnostic_config_has_no_embedded_default_credential(self):
  source=(ROOT/'src/android/app/src/main/java/com/openminis/app/plus/Diag.kt').read_text()
  self.assertNotIn('DEFAULT_CODE_JSON',source)
  config=source.split('private fun config(): DiagCore.Config?',1)[1].split('private fun restartIfOn',1)[0]
  self.assertIn('return null',config)
  self.assertNotIn('parseCode("',config)

class TraceTest(unittest.TestCase):
 def test_aggregate_only_and_malformed_lines(self):
  with tempfile.TemporaryDirectory() as td0:
   p=Path(td0)/'trace.jsonl'; p.write_text('\n'.join([json.dumps({'tool':'shell_execute','duration_ms':12,'outcome':'success','arguments':'SUPERSECRET'}),json.dumps({'tool':'shell_execute','duration_ms':18,'outcome':'timeout','result':'TOKENXYZ'}),'not-json']))
   result=td.summarize(p); self.assertEqual(result['records'],2); self.assertEqual(result['malformed_lines'],1)
   self.assertEqual(result['tools'][0]['avg_duration_ms'],15.0); self.assertEqual(result['tools'][0]['non_success'],1)
   self.assertNotIn('SUPERSECRET',json.dumps(result)); self.assertNotIn('TOKENXYZ',json.dumps(result))
 def test_arbitrary_identifiers_and_nonfinite_durations_are_not_echoed(self):
  with tempfile.TemporaryDirectory() as td0:
   p=Path(td0)/'trace.jsonl'; p.write_text(json.dumps({'tool':'api_key=SECRET_VALUE','duration_ms':float('nan'),'outcome':'SUCCESS'})+'\n')
   result=td.summarize(p); rendered=json.dumps(result,allow_nan=False)
   self.assertNotIn('SECRET_VALUE',rendered); self.assertEqual(result['tools'][0]['tool'],'other')
   self.assertEqual(result['tools'][0]['avg_duration_ms'],0)
if __name__=='__main__':unittest.main()
