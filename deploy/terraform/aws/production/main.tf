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
# One environment of the notification service on AWS (ADR-028): a three-zone VPC, EKS for the services and Pulsar,
# and the datastores module for RDS PostgreSQL, ElastiCache and the cross-region backups.

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name               = "notification-${var.environment}"
  availability_zones = slice(data.aws_availability_zones.available.names, 0, 3)

  internet_egress_ports = [443, 25, 465, 587]

  tags = {
    Application = "notification-service"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.7"

  name = local.name
  cidr = var.vpc_cidr
  azs  = local.availability_zones

  private_subnets     = [for index in range(3) : cidrsubnet(var.vpc_cidr, 4, index)]
  public_subnets      = [for index in range(3) : cidrsubnet(var.vpc_cidr, 8, 48 + index)]
  database_subnets    = [for index in range(3) : cidrsubnet(var.vpc_cidr, 8, 64 + index)]
  elasticache_subnets = [for index in range(3) : cidrsubnet(var.vpc_cidr, 8, 80 + index)]

  create_database_subnet_group    = false
  create_elasticache_subnet_group = false

  enable_nat_gateway     = true
  one_nat_gateway_per_az = true

  enable_flow_log                                 = true
  create_flow_log_cloudwatch_log_group            = true
  create_flow_log_cloudwatch_iam_role             = true
  flow_log_cloudwatch_log_group_retention_in_days = 90

  public_subnet_tags  = { "kubernetes.io/role/elb" = "1" }
  private_subnet_tags = { "kubernetes.io/role/internal-elb" = "1" }
}

# The dispatcher must reach providers anywhere on the internet, so nodes keep egress to 0.0.0.0/0, but only on HTTPS
# and SMTP. Which pods may use it, and that private ranges stay unreachable, is enforced per pod by the chart's
# network policies (ADR-022, ADR-025). Everything else is limited to the VPC.
#trivy:ignore:AVD-AWS-0104
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 21.26"

  name               = local.name
  kubernetes_version = var.kubernetes_version
  enabled_log_types  = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  node_security_group_additional_rules = merge(
    {
      egress_all = {
        description = "Anything inside the VPC: datastores, Pulsar, the control plane and AWS endpoints"
        protocol    = "-1"
        from_port   = 0
        to_port     = 0
        type        = "egress"
        cidr_blocks = [var.vpc_cidr]
      }
    },
    {
      for port in local.internet_egress_ports : "egress_internet_${port}" => {
        description = "Providers, image registries and AWS APIs on ${port}/tcp"
        protocol    = "tcp"
        from_port   = port
        to_port     = port
        type        = "egress"
        cidr_blocks = ["0.0.0.0/0"]
      }
    },
  )

  endpoint_public_access       = length(var.cluster_endpoint_public_access_cidrs) > 0
  endpoint_public_access_cidrs = var.cluster_endpoint_public_access_cidrs

  enable_cluster_creator_admin_permissions = true

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  addons = {
    coredns = {}
    eks-pod-identity-agent = {
      before_compute = true
    }
    kube-proxy = {}
    vpc-cni = {
      before_compute = true
      configuration_values = jsonencode({
        enableNetworkPolicy = "true"
      })
    }
    aws-ebs-csi-driver = {
      pod_identity_association = [{
        role_arn        = aws_iam_role.ebs_csi_driver.arn
        service_account = "ebs-csi-controller-sa"
      }]
    }
  }

  eks_managed_node_groups = {
    general = {
      ami_type       = "AL2023_x86_64_STANDARD"
      instance_types = var.node_instance_types
      min_size       = var.node_count.minimum
      desired_size   = var.node_count.desired
      max_size       = var.node_count.maximum
    }
  }
}

module "datastores" {
  source = "../modules/datastores"

  providers = {
    aws                   = aws
    aws.disaster_recovery = aws.disaster_recovery
  }

  name                      = local.name
  vpc_id                    = module.vpc.vpc_id
  database_subnet_ids       = module.vpc.database_subnets
  cache_subnet_ids          = module.vpc.elasticache_subnets
  client_security_group_ids = [module.eks.node_security_group_id]
  tags                      = local.tags
}
