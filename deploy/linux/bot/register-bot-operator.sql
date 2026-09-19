\set ON_ERROR_STOP on
BEGIN;
INSERT INTO admin_operators (operator_id, display_name)
VALUES ('sanghyuk-bot-operator', '이상혁 · 시장 봇 운영')
ON CONFLICT (operator_id) DO NOTHING;
INSERT INTO operator_roles (operator_id, role)
VALUES ('sanghyuk-bot-operator', 'VIEWER'), ('sanghyuk-bot-operator', 'BOT_OPS')
ON CONFLICT (operator_id, role) DO NOTHING;
INSERT INTO admin_audit_log (operator_id, action, target, result, detail)
VALUES ('sanghyuk-bot-operator', 'operator.bootstrap', 'sanghyuk-bot-operator', 'OK',
        '{"roles":["VIEWER","BOT_OPS"],"method":"operator-approved SQL"}');
COMMIT;
