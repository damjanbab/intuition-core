# M-04 Mission Notes

```clojure
user=> (bootstrap! {:mission/id "M-04-1763475981743" :agent/id "scribe" })
{:sandbox/paths
 {:root
  "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe",
  :work
  "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe/work",
  :artifacts
  "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe/artifacts",
  :logs
  "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe/logs",
  :evidence
  "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe/evidence",
  :tmp
  "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe/tmp",
  :secrets
  "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe/secrets"},
 :mission/id "M-04-1763475981743",
 :env
 {:INTUITION_SANDBOX_ROOT
  "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe",
  :INTUITION_SANDBOX_WORK
  "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe/work",
  :INTUITION_MISSION_ID "M-04-1763475981743",
  :INTUITION_AGENT_ID "scribe",
  :INTUITION_SANDBOX_PORTS "42767,42768"},
 :manifest/path
 "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe/manifest.edn",
 :agent/id "scribe",
 :cleanup!
 #object[intuition.sfs.env.bootstrap$bootstrap_BANG_$cleanup_BANG___230 0x1db077a4 "intuition.sfs.env.bootstrap$bootstrap_BANG_$cleanup_BANG___230@1db077a4"],
 :sandbox/ports [42767 42768],
 :action/status :status/ok,
 :sandbox/root
 "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe"}

user=> (log-step! {:mission/id "M-04-1763475981743", :step/id "STEP-01", :track/id :worktrack/code, :lock/token "fd222ee2-2d49-4864-8683-d6133226d57f", :agent/id "scribe", :summary "Transcribed sandbox bootstrap + log", :deliverable/id "src/demo.clj"} )
{:action/status :status/ok,
 :log/id #uuid "495885cd-1010-40da-b4b4-34d64e84d22e",
 :worklog/entity
 [:worklog/id #uuid "495885cd-1010-40da-b4b4-34d64e84d22e"],
 :markdown/path
 "/home/dami/intuition-core/missions/logs/m-04-1763475981743/worklog.md",
 :datomic/tx
 {:db-before
  #datomic.core.db.Db{:id "intuition-core", :basisT 7, :indexBasisT 0, :index-root-id nil, :asOfT nil, :sinceT nil, :raw nil},
  :db-after
  #datomic.core.db.Db{:id "intuition-core", :basisT 8, :indexBasisT 0, :index-root-id nil, :asOfT nil, :sinceT nil, :raw nil},
  :tx-data
  [#datom[13194139533320 50 #inst "2025-11-18T14:26:22.496-00:00" 13194139533320 true] #datom[74766790688855 77 "src/demo.clj" 13194139533320 true] #datom[74766790688855 73 #uuid "495885cd-1010-40da-b4b4-34d64e84d22e" 13194139533320 true] #datom[74766790688855 79 "Transcribed sandbox bootstrap + log" 13194139533320 true] #datom[74766790688855 83 "{:label \"artifact\", :path \"/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe/artifacts/artifact.txt\"}" 13194139533320 true] #datom[74766790688855 81 "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe/evidence/before.txt" 13194139533320 true] #datom[74766790688855 74 "M-04-1763475981743" 13194139533320 true] #datom[74766790688855 76 "scribe" 13194139533320 true] #datom[74766790688855 78 :worktrack/code 13194139533320 true] #datom[74766790688855 82 "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe/evidence/after.txt" 13194139533320 true] #datom[74766790688855 75 "STEP-01" 13194139533320 true] #datom[74766790688855 80 "fd222ee2-2d49-4864-8683-d6133226d57f" 13194139533320 true] #datom[74766790688855 85 #inst "2025-11-18T14:26:22.485-00:00" 13194139533320 true] #datom[74766790688855 84 "/home/dami/intuition-core/missions/logs/m-04-1763475981743/worklog.md" 13194139533320 true]]},
 :artifacts
 [{:path
   "/home/dami/intuition-core/tmp/missions/m-04-1763475981743/scribe/artifacts/artifact.txt",
   :label "artifact"}]}
```
