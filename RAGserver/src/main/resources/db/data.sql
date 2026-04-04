INSERT INTO t_user (username, password_hash, display_name, status)
VALUES ('demo01', '123456', '演示用户A', 1),
       ('demo02', '123456', '演示用户B', 1);

INSERT INTO t_document (user_id, document_name, source_path, status)
VALUES (1, '客服手册V1.pdf', '/docs/customer-service-v1.pdf', 1),
       (2, '售后流程说明.pdf', '/docs/after-sales-process.pdf', 1);

INSERT INTO t_knowledge_item (question, answer, status)
VALUES ('如何查询订单状态？', '请在订单详情页查看物流与处理进度，或提供订单号给客服代查。', 1),
       ('订单可以退款吗？', '支持退款。若未发货可直接退款，已发货需先提交退货申请并经审核后退款。', 1),
       ('快递单号多久更新？', '一般发货后2到6小时会同步快递单号，节假日可能略有延迟。', 1),
       ('忘记密码怎么办？', '请在登录页点击“忘记密码”，通过手机号或邮箱验证后重置密码。', 1),
       ('如何联系客服？', '可在帮助中心提交工单，或在工作时间通过在线客服入口联系人工客服。', 1),
       ('发票如何开具？', '在订单完成后进入订单详情选择“申请发票”，按提示填写抬头和税号信息。', 1);

INSERT INTO t_chat_session (user_id, title, status)
VALUES (1, '订单咨询', 1),
       (1, '退款问题', 1),
       (2, '物流查询', 1);

INSERT INTO t_chat_message (session_id, role, content, token_count)
VALUES (1, 'user', '你好，我想查询订单状态。', 12),
       (1, 'assistant', '您好，请提供订单号，我来为您查询。', 18),
       (2, 'user', '我的订单可以退款吗？', 11),
       (2, 'assistant', '可以的，请告知下单时间和商品信息。', 16),
       (3, 'user', '快递单号多久能更新？', 10),
       (3, 'assistant', '通常发货后2-6小时更新，请稍后再试。', 17);
