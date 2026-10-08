--liquibase formatted sql

--changeset evals:0032-frustration-call-site-ids
-- Frustration now uses classifier.call_site_ids like every other classifier, so NULL runs it on every
-- call site. Its picks move there, sorted, and frustration_scope goes. A Frustration classifier with no
-- picks stays NULL, so it now scores every call site instead of none. 0030's per-call-site keys stay:
-- a session is still a conversation on one call site.
UPDATE classifier c
   SET call_site_ids = p.ids
  FROM (SELECT classifier_id, array_agg(call_site_id ORDER BY call_site_id) AS ids
          FROM frustration_scope
         GROUP BY classifier_id) p
 WHERE c.id = p.classifier_id;

DROP TABLE frustration_scope;

--rollback CREATE TABLE frustration_scope (
--rollback     project_id text NOT NULL,
--rollback     classifier_id text NOT NULL,
--rollback     call_site_id text NOT NULL,
--rollback     created_at timestamp with time zone DEFAULT now() NOT NULL,
--rollback     CONSTRAINT frustration_scope_pkey PRIMARY KEY (project_id, classifier_id, call_site_id),
--rollback     CONSTRAINT frustration_scope_project_id_fkey
--rollback         FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
--rollback     CONSTRAINT frustration_scope_classifier_id_fkey
--rollback         FOREIGN KEY (classifier_id) REFERENCES classifier(id) ON DELETE CASCADE
--rollback );
--rollback INSERT INTO frustration_scope (project_id, classifier_id, call_site_id)
--rollback     SELECT c.project_id, c.id, unnest(c.call_site_ids) FROM classifier c
--rollback      WHERE c.detector = 'frustration' AND c.call_site_ids IS NOT NULL;
--rollback UPDATE classifier SET call_site_ids = NULL WHERE detector = 'frustration';
