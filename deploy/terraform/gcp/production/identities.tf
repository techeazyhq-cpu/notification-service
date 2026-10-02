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
# Workload Identity: one Google service account per workload, impersonable only by its Kubernetes service account in
# the release namespace, and allowed only what that workload does. The services themselves get no Google identity:
# they read their secrets from the Kubernetes Secret that External Secrets writes.

locals {
  workload_service_accounts = {
    secrets-sync = "notification-external-secrets"
    db-dump      = "notification-logical-backup"
  }
}

resource "google_service_account" "workloads" {
  for_each = local.workload_service_accounts

  account_id   = "${local.service_account_prefix}-${each.key}"
  display_name = "${each.key} of ${local.name}"
}

resource "google_service_account_iam_member" "workload_identity" {
  for_each = local.workload_service_accounts

  service_account_id = google_service_account.workloads[each.key].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[${var.namespace}/${each.value}]"

  depends_on = [google_container_cluster.cluster]
}

resource "google_secret_manager_secret_iam_member" "external_secrets_reads" {
  for_each = module.datastores.secret_ids

  secret_id = each.value
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.workloads["secrets-sync"].email}"
}

resource "google_storage_bucket_iam_member" "logical_backup_writes" {
  bucket = module.datastores.backup_bucket_name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${google_service_account.workloads["db-dump"].email}"
}
