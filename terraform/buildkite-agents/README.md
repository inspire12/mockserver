# Buildkite Agents

Terraform configuration for MockServer's Buildkite CI build agent infrastructure, using the official [Buildkite Elastic CI Stack for AWS](https://github.com/buildkite/terraform-buildkite-elastic-ci-stack-for-aws) module.

## Architecture

```mermaid
flowchart TB
    subgraph "Buildkite Cloud"
        BK_API[Buildkite API]
        BK_QUEUE[Job Queue<br/>'default']
    end

    subgraph "AWS eu-west-2"
        subgraph "VPC (auto-created)"
            subgraph "AutoScaling Group"
                EC2_1[EC2 m7i.2xlarge<br/>Buildkite Agent<br/>20% on-demand / 80% spot]
                EC2_2[EC2 m6a.2xlarge<br/>Buildkite Agent<br/>20% on-demand / 80% spot]
            end
        end
        SCALER[Lambda Autoscaler<br/>Runs every minute]
        SSM[SSM Parameter Store<br/>Agent Token]
        S3_SECRETS[S3 Secrets Bucket]
        CW_ALARMS[CloudWatch Alarms<br/>Capacity gaps, scaler errors]
        SNS[SNS Topic<br/>Email alerts]
    end
    
    CW_ALARMS -->|alert| SNS

    BK_API -->|queue depth| SCALER
    SCALER -->|set desired 0–10| EC2_1 & EC2_2
    EC2_1 & EC2_2 -->|poll for jobs| BK_QUEUE
    EC2_1 & EC2_2 -->|read token| SSM
    EC2_1 & EC2_2 -->|read secrets| S3_SECRETS
```

## How It Works

```mermaid
sequenceDiagram
    participant BK as Buildkite
    participant Lambda as Autoscaler Lambda
    participant ASG as AutoScaling Group
    participant Agent as EC2 Agent
    participant Docker as Docker Container

    loop Every 60 seconds
        Lambda->>BK: Check job queue depth
        Lambda->>ASG: Set desired capacity (0–10)
    end

    BK->>Agent: Job available
    Agent->>Docker: docker pull mockserver/mockserver:maven
    Agent->>Docker: docker run (mount repo, run build)
    Docker->>Docker: mvnw clean install
    Docker-->>BK: Upload artifacts (*.log)

    Note over Agent,ASG: When idle, agents self-terminate<br/>ASG scales back to 0
```

## Directory Structure

```
buildkite-agents/
├── bootstrap/               # One-time state backend setup
│   ├── main.tf              #   S3 bucket
│   └── README.md            #   Bootstrap instructions
├── main.tf                  # Elastic CI Stack module
├── monitoring.tf            # CloudWatch alarms, SNS notifications, dashboard
├── backend.tf               # S3 remote state configuration
├── build-secrets.tf         # Docker Hub secret + Buildkite agent IAM policy
├── ecr-public.tf            # ECR Public repository + push IAM policy
├── ecr-pull-through-cache.tf # ECR pull-through cache (docker-hub/quay; mcr unsupported) + credential secret + agent IAM
├── perf-results.tf          # S3 bucket (mockserver-ci-perf-results) + IAM policy for perf queue
├── variables.tf             # Input variables
├── outputs.tf               # Outputs (ASG name, VPC ID, dashboard URL, perf queue outputs)
├── versions.tf              # Terraform + provider versions
├── terraform.tfvars.example # Example variable values
├── run.sh                   # Wrapper script (auth + plan/apply)
└── README.md                # This file
```

## Prerequisites

1. **Terraform** >= 1.5 — `brew install terraform`
2. **AWS CLI** — `brew install awscli`
3. **AWS SSO profile** `mockserver-build` configured:
   ```bash
   aws configure sso --profile mockserver-build
    # SSO region: eu-west-2
   # Default region: eu-west-2
   ```
4. **Buildkite agent token** — from https://buildkite.com/organizations/mockserver/agents

## Getting Started

### 1. Bootstrap the State Backend (first time only)

```bash
./run.sh bootstrap
```

This creates the S3 bucket used for remote state. Uses `import` blocks so it's safe to re-run against existing resources. See [bootstrap/README.md](bootstrap/) for details.

### 2. Configure Variables

```bash
cp terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars` and set your Buildkite agent token:

```hcl
buildkite_agent_token = "your-token-here"
```

> **terraform.tfvars is gitignored** — it contains secrets and must never be committed.

### 3. Preview Changes

```bash
./run.sh plan
```

### 4. Apply

```bash
./run.sh apply
```

## run.sh Reference

The `run.sh` wrapper handles AWS SSO authentication, environment workarounds (corporate TLS proxy, macOS pyexpat), and runs Terraform commands.

```
Usage: run.sh [command]

Commands:
  plan       Run terraform plan (default)
  apply      Run terraform apply
  destroy    Run terraform destroy
  bootstrap  Initialise the S3 state bucket
  init       Run terraform init
```

```mermaid
flowchart LR
    A[run.sh] --> B{AWS SSO<br/>authenticated?}
    B -->|Yes| D[terraform init]
    B -->|No| C[Prompt: aws sso login]
    C --> B
    D --> E{Command}
    E -->|plan| F[terraform plan]
    E -->|apply| G[terraform apply]
    E -->|destroy| H[terraform destroy]
    E -->|bootstrap| I[bootstrap/terraform apply]
```

## Variables

| Variable | Type | Default | Description |
|----------|------|---------|-------------|
| `buildkite_agent_token` | `string` | *(required)* | Buildkite agent registration token |
| `region` | `string` | `eu-west-2` | AWS region |
| `instance_types` | `string` | `m7i.2xlarge` | EC2 instance types, all 8 vCPU / 32 GiB; the first is used for on-demand, Spot picks from all (tfvars: `m7i.2xlarge,m6a.2xlarge,m6i.2xlarge,m7a.2xlarge`) |
| `min_size` | `number` | `0` | Minimum instances (0 = scale to zero) |
| `max_size` | `number` | `10` | Maximum instances |
| `on_demand_percentage` | `number` | `20` | % on-demand vs spot (20 = 20% on-demand fallback) |
| `perf_instance_types` | `string` | `c5.12xlarge` | EC2 instance type for the perf queue. Must have enough PHYSICAL cores for the run's cpusets — see the note on the variable; `perf-test-run.sh` fails the build if they overlap |
| `perf_min_size` | `number` | `0` | Minimum perf queue instances (must remain 0) |
| `perf_max_size` | `number` | `3` | Maximum perf queue instances. One agent per instance, so concurrent perf jobs always run on separate machines |
| `perf_xl_instance_types` | `string` | `c6i.32xlarge` | EC2 instance type for the perf-xl queue (a single fixed type) |
| `perf_xl_min_size` | `number` | `0` | Minimum perf-xl queue instances (must remain 0) |
| `perf_xl_max_size` | `number` | `1` | Maximum perf-xl queue instances |
| `alert_email` | `string` | `""` | Email address for infrastructure alerts |

## Outputs

| Output | Description |
|--------|-------------|
| `auto_scaling_group_name` | Name of the agent AutoScaling Group |
| `vpc_id` | VPC ID where agents run |
| `lambda_scaler_arn` | ARN of the Lambda autoscaler function |
| `dashboard_url` | CloudWatch Dashboard URL for agent monitoring |
| `sns_topic_arn` | SNS topic ARN for infrastructure alerts |
| `perf_auto_scaling_group_name` | Name of the perf queue AutoScaling Group |
| `perf_lambda_scaler_arn` | ARN of the perf queue Lambda autoscaler |
| `perf_xl_auto_scaling_group_name` | Name of the perf-xl queue AutoScaling Group |
| `perf_xl_lambda_scaler_arn` | ARN of the perf-xl queue Lambda autoscaler |
| `perf_results_bucket` | Name of the S3 bucket storing perf regression run history |

## Monitoring and Alerts

The infrastructure includes CloudWatch alarms and SNS email notifications for:

- **ASG capacity gap**: Desired capacity not met for 5+ minutes (EC2 launch failures or Spot unavailability)
- **Lambda scaler errors**: Autoscaler function errors
- **Lambda scaler not invoked**: EventBridge schedule broken
- **ASG launch failures**: EventBridge rule captures failed EC2 launches

**CloudWatch Dashboard**: View real-time agent capacity, scaler health, and recent logs via the dashboard URL output.

**Email Alerts**: Set `alert_email` in `terraform.tfvars` to receive SNS notifications. You'll need to confirm the subscription via email after first apply.

## Agent Queues

Five agent queues separate workloads by resource needs:

| Queue | Instance | Capacity mix | Max | Agents/instance | Purpose |
|-------|----------|-------------|-----|-----------------|---------|
| `default` | m7i.2xlarge / m6a.2xlarge / m6i.2xlarge / m7a.2xlarge | 20% on-demand / 80% Spot | 10 | 1 | Build and test (Maven, Docker, k3d) |
| `trigger` | t3.small / t3a.small / t3.micro | 100% Spot | 4 | 4 | Trigger polling jobs (`sleep` + `curl` loops) |
| `release` | Same as `default` | 100% on-demand | 2 | 1 | Release pipeline steps with release secrets |
| `perf` | c5.12xlarge | 100% on-demand | 3 | 1 | Daily performance-regression benchmarks (k6 + JMH); up to three perf jobs at once, each with a whole machine to itself. 24 physical cores, so the server, upstream and k6 cpusets land on genuinely disjoint cores |
| `perf-xl` | c6i.32xlarge | 100% on-demand | 1 | 1 | Performance runs that need more cores than one `perf` box: 128 vCPU across 64 physical cores. Same rules as `perf`; same policies minus the write API token |

All queues have `min_size = 0` (scale-to-zero). This is a hard constraint — do not set `min_size` to a non-zero value.

The `perf` queue uses `perf-results.tf` (S3 bucket `mockserver-ci-perf-results` + IAM policy `buildkite-perf-results`) to persist historical run JSON for rolling-baseline comparison. The `perf` stack is defined as `module "buildkite_perf_stack"` in `main.tf`; `perf-xl` is `module "buildkite_perf_xl_stack"` and shares the same bucket and policy. A new queue also needs a cluster queue of the same key in `terraform/buildkite-pipelines/clusters.tf`, applied first, or its agents cannot register.

**Rolling back perf-xl.** The clean rollback is `perf_xl_max_size = 0`, which stops any instance launching. Full removal also deletes the `perf-xl` entry from `agent_vpc_ids_by_stack` in `security-hardening.tf`. Before destroying, empty the module's versioned managed-secrets and secrets-logging S3 buckets, including every object version. Remove the `perf-xl` cluster queue from `terraform/buildkite-pipelines/clusters.tf` only after the agents stack is gone.

## Cost

Current configuration (`min_size = 0`, `on_demand_percentage = 20`, diversified instance types):
- **Idle cost:** $0 for instances (every queue scales to zero when no builds are queued), plus about $0.066/hr per stack for its VPC's three SSM interface endpoints, plus minimal CloudWatch alarm costs
- **Build cost:** ~$0.03–0.10/hr per agent (20% on-demand, 80% spot, c5/m5 family)
- **Perf queue cost:** ~$2.42/hr when active (c5.12xlarge on-demand, eu-west-2 — verified against the AWS Pricing API), runs at most once per day when master has new commits. It was c5.4xlarge at $0.81/hr; the move is a measurement-correctness fix, because eight physical cores could not hold the run's thirteen-core cpusets and the load generator shared cores with the server it was measuring
- **Perf-xl queue cost:** $6.464/hr when active (c6i.32xlarge on-demand, eu-west-2, AWS Pricing API). Max 1 instance. Its VPC endpoints add about $0.066/hr even at zero instances, as every stack's do
- **Monitoring cost:** <$1/month (alarms + dashboard + SNS)
- Agents take 2–3 minutes to launch from cold start

## Reliability Improvements

The infrastructure is designed to handle EC2 Spot capacity fluctuations:

1. **Diversified instance types**: Multiple instance families and sizes (c5, c5a, m5)
2. **On-demand fallback**: 20% on-demand capacity ensures builds can start even when Spot is unavailable
3. **On-demand base**: Always launches at least 1 on-demand instance when scaling up
4. **Proactive monitoring**: Alerts notify you of capacity issues before builds are blocked
