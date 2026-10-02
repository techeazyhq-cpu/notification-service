# Copyright 2026 Vasantha Kumar
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# @author Vasantha Kumar <vasantha.kumar@hotmail.com>
#
# The managed-service counterpart of postgres-recovery.rules.yml: RDS reports failed backups, failovers, failures and
# low storage to one topic. Subscribe the on-call channel to it (e-mail, a chat webhook, or an incident tool).

resource "aws_sns_topic" "operations" {
  name              = "${var.name}-operations"
  kms_master_key_id = aws_kms_key.primary.arn
  tags              = var.tags
}

resource "aws_sns_topic_policy" "operations" {
  arn = aws_sns_topic.operations.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "RdsEventsMayPublish"
      Effect    = "Allow"
      Principal = { Service = "events.rds.amazonaws.com" }
      Action    = "sns:Publish"
      Resource  = aws_sns_topic.operations.arn
      Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id } }
    }]
  })
}

resource "aws_db_event_subscription" "postgres" {
  name             = "${var.name}-postgres"
  sns_topic        = aws_sns_topic.operations.arn
  source_type      = "db-instance"
  source_ids       = [aws_db_instance.postgres.identifier]
  event_categories = ["backup", "failover", "failure", "low storage", "recovery", "maintenance"]
  tags             = var.tags

  depends_on = [aws_sns_topic_policy.operations]
}
