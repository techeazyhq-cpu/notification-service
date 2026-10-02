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
# EKS Pod Identity roles, each bound to one service account and allowed only what that workload does. The services
# themselves get no AWS role: they read their secrets from the Kubernetes Secret that External Secrets writes.
# External Secrets calls AWS with its controller's credentials, so its role is bound to the controller's service
# account (Helm chart defaults) and can read only this deployment's two secrets.

locals {
  pod_identity_trust_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "pods.eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
}

resource "aws_iam_role" "ebs_csi_driver" {
  name               = "${local.name}-ebs-csi-driver"
  assume_role_policy = local.pod_identity_trust_policy
}

resource "aws_iam_role_policy_attachment" "ebs_csi_driver" {
  role       = aws_iam_role.ebs_csi_driver.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

resource "aws_iam_role" "external_secrets" {
  name               = "${local.name}-external-secrets"
  assume_role_policy = local.pod_identity_trust_policy
}

resource "aws_iam_role_policy" "external_secrets" {
  name = "read-notification-secrets"
  role = aws_iam_role.external_secrets.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadTheApplicationAndSchemaOwnerSecrets"
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = [module.datastores.application_secret_arn, module.datastores.postgres_owner_secret_arn]
      },
      {
        Sid      = "DecryptThem"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = [module.datastores.primary_kms_key_arn]
      },
    ]
  })
}

resource "aws_eks_pod_identity_association" "external_secrets" {
  cluster_name    = module.eks.cluster_name
  namespace       = "external-secrets"
  service_account = "external-secrets"
  role_arn        = aws_iam_role.external_secrets.arn
}

resource "aws_iam_role" "logical_backup" {
  name               = "${local.name}-logical-backup"
  assume_role_policy = local.pod_identity_trust_policy
}

resource "aws_iam_role_policy" "logical_backup" {
  name = "write-logical-dumps"
  role = aws_iam_role.logical_backup.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "WriteAndReadDumps"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:ListBucket"]
        Resource = [module.datastores.backup_bucket_arn, "${module.datastores.backup_bucket_arn}/*"]
      },
      {
        Sid      = "EncryptWithTheRecoveryRegionKey"
        Effect   = "Allow"
        Action   = ["kms:GenerateDataKey", "kms:Decrypt"]
        Resource = [module.datastores.disaster_recovery_kms_key_arn]
      },
    ]
  })
}

resource "aws_eks_pod_identity_association" "logical_backup" {
  cluster_name    = module.eks.cluster_name
  namespace       = var.namespace
  service_account = "notification-logical-backup"
  role_arn        = aws_iam_role.logical_backup.arn
}
