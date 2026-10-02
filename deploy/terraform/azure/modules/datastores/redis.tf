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
# Redis holds only rate-limit counters and caches, rebuilt on their own after a loss (ADR-026), so it has no
# persistence. Managed Redis has no anonymous access; the services authenticate with the database's access key.

resource "azurerm_managed_redis" "redis" {
  name                      = "${var.name}-redis"
  resource_group_name       = var.resource_group_name
  location                  = var.location
  sku_name                  = var.redis_sku_name
  high_availability_enabled = true
  public_network_access     = "Disabled"

  default_database {
    client_protocol                    = "Encrypted"
    clustering_policy                  = "EnterpriseCluster"
    access_keys_authentication_enabled = true
    eviction_policy                    = "VolatileLRU"
  }

  tags = var.tags
}
