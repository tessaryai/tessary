# Deploy the Groundedness model on AWS

Deploy Tessary's Groundedness model on a GPU instance beside a Tessary that runs on AWS, then hand
the operator the lines that point Tessary at it.

Rules for the whole run:

- **Report progress.** One short line after each step, so the user can follow along.
- **Stop and ask on any blocker or decision.** A missing permission, a quota that is too low, a
  stack that fails to create. Never guess a region, a network, or a security group.
- **Never touch the running Tessary.** You create the model's stack and print what the operator
  runs on Tessary. You never edit Tessary's `.env` or restart it yourself.

`serve.py SHA-256: 70128aafdac8c44bff4dd17b8639cba2cf89d6ab9f7c5708ac906125cb62a71c`

`groundedness-aws.yaml SHA-256: 4d34085ad5e3c4d68704d313b66e65fb9f8cdd87c5527d96fc4fdc10863fe1b6`

## Which version

Take `<ref>` from the URL you were given for this file: the path segment after `/blob/`, for
example `v1.3.0` or `main`. Every download below uses that `<ref>`, so the model server matches the
Tessary version that linked here.

## 1. What this does

It creates one CloudFormation stack, `groundedness-model`, in the AWS region and VPC Tessary runs
in. The stack holds a GPU instance that runs the Groundedness model. A schedule starts the instance
every hour; Tessary scores everything that arrived since its last run; the instance stops itself
after 10 minutes with no requests. The GPU is billed only while the instance runs.

## 2. Requirements

- **Tessary runs on AWS.** For other hosts this setup does not apply.
- **The AWS CLI is signed in** to the account Tessary runs in:

  ```bash
  aws sts get-caller-identity
  ```

- **Where Tessary runs:** its region, VPC, a subnet, and the security group of the Tessary
  instance. Ask the user, or read them from the instance they name:

  ```bash
  aws ec2 describe-instances --region <region> --instance-ids <tessary-instance-id> \
    --query 'Reservations[0].Instances[0].{Vpc:VpcId,Subnet:SubnetId,Groups:SecurityGroups}'
  ```

  The model's subnet must reach the internet (a NAT gateway, or public IPs on launch) to download
  the model and its packages. Tessary's own subnet usually works. Confirm the choice with the user.
- **GPU quota.** The account's quota for running G instances must be 4 vCPUs or more:

  ```bash
  aws service-quotas get-service-quota --region <region> --service-code ec2 --quota-code L-DB2E81BA \
    --query Quota.Value
  ```

  If it is under 4, tell the user to request an increase in Service Quotas, and stop.

If any requirement fails, tell the user which one and stop.

## 3. Plan and cost

Show the user what the stack creates:

- a GPU instance, `g4dn.xlarge` by default (`g5.xlarge` and `g6.xlarge` are allowed), with a 100 GB
  disk, in the subnet above
- a security group that lets only Tessary's security group reach port 18080
- a secret holding the key Tessary uses to call the model
- an IAM role that reads that secret, and one that lets the schedule start the instance
- an hourly schedule that starts the instance

Then the cost. Look up the current on-demand hourly price of the instance type in this region; don't
use a remembered number. Each wake runs for the boot and model load (a few minutes), the scoring,
and 10 idle minutes, so expect roughly 15 to 20 minutes an hour at low traffic. Show:

- instance: the hourly price times the expected minutes an hour, per month
- disk: 100 GB of gp3, billed all month, running or stopped
- the secret: one Secrets Manager secret, per month

Ask the user for a yes before continuing.

## 4. Get the template

```bash
mkdir -p ~/.tessary/groundedness
curl -fsSL -o ~/.tessary/groundedness/groundedness-aws.yaml \
  https://raw.githubusercontent.com/tessaryai/tessary/<ref>/classifiers/groundedness/setup/groundedness-aws.yaml
shasum -a 256 ~/.tessary/groundedness/groundedness-aws.yaml
```

The checksum must equal the `groundedness-aws.yaml SHA-256` line at the top of this file. If it
differs, delete the download, tell the user, and stop.

## 5. Create the stack

```bash
aws cloudformation deploy --region <region> --stack-name groundedness-model \
  --template-file ~/.tessary/groundedness/groundedness-aws.yaml \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
    VpcId=<vpc> SubnetId=<subnet> TessarySecurityGroupId=<tessary-security-group> \
    InstanceType=g4dn.xlarge \
    ServePyUrl=https://raw.githubusercontent.com/tessaryai/tessary/<ref>/classifiers/groundedness/serve.py \
    ServePySha256=<the serve.py SHA-256 line at the top of this file>
```

It waits until the stack is created. If it fails, show the failed events and stop:

```bash
aws cloudformation describe-stack-events --region <region> --stack-name groundedness-model \
  --query "StackEvents[?contains(ResourceStatus, 'FAILED')].[LogicalResourceId,ResourceStatusReason]"
```

Read the outputs:

```bash
aws cloudformation describe-stacks --region <region> --stack-name groundedness-model \
  --query 'Stacks[0].Outputs'
```

## 6. Start it once now

```bash
aws ec2 start-instances --region <region> --instance-ids <InstanceId>
```

The instance starts right after the stack is created, so this may already be done. The first boot
installs PyTorch and downloads the model, which takes up to 15 minutes. Then the model answers:

```bash
curl -s http://<PrivateIp>:18080/healthz
```

It is ready when the answer lists `"groundedness"` under `heads`. This works only from inside the
VPC, from the Tessary instance's security group. If you can't reach the private IP from here, ask
the user to run it on the Tessary host; that also proves Tessary can reach the model.

The model stops itself after 10 idle minutes. If it stopped before the check, start it again.

## 7. Hand over

Read the key:

```bash
aws secretsmanager get-secret-value --region <region> --secret-id <KeySecretArn> \
  --query SecretString --output text
```

Print these three lines for the operator to put in Tessary's `.env`, replacing any existing line for
the same key:

```bash
TESSARY_OBSERVER_ENCODER_URL=http://<PrivateIp>:18080
TESSARY_OBSERVER_ENCODER_API_KEY=<the key>
TESSARY_GROUNDEDNESS_CLASSIFIER_MODE=production
```

Then Tessary's restart command, run from the directory that holds that `.env`:

```bash
docker compose -f oci://docker.io/tessaryai/tessary:compose up -d -y
```

If Tessary was started from a checkout, the command is `docker compose up -d` in the checkout.

Tell the operator to make both changes. Once Tessary restarts, Groundedness scores within the hour.
Do not run either yourself.

## Restart

Use this when the model was set up before and Tessary shows it is not scoring.

1. Check the stack exists:

   ```bash
   aws cloudformation describe-stacks --region <region> --stack-name groundedness-model \
     --query 'Stacks[0].[StackStatus,Outputs]'
   ```

   If the stack is gone, set it up again from [step 4](#4-get-the-template). The new stack has a
   new key and a new private IP, so hand over new `.env` lines as in [step 7](#7-hand-over).
2. Start the instance as in [step 6](#6-start-it-once-now) and wait for `/healthz`.
3. If `/healthz` answers but Tessary still doesn't score, compare Tessary's
   `TESSARY_OBSERVER_ENCODER_URL` and `TESSARY_OBSERVER_ENCODER_API_KEY` with the stack's outputs and
   secret, and hand the operator the lines from [step 7](#7-hand-over).

## Stop scoring and charges

```bash
aws cloudformation delete-stack --region <region> --stack-name groundedness-model
```

This deletes the instance, its disk, the schedule, and the key. Turning Groundedness off in Tessary
does not stop the instance: the schedule keeps starting it every hour until the stack is deleted.

## Troubleshooting

| Problem | Action |
| --- | --- |
| The stack fails on the instance with a capacity error | The instance type has no capacity in that subnet's zone. Try another subnet in the VPC, or `g5.xlarge` or `g6.xlarge`. |
| The stack fails with a quota error | Request a higher G-instance vCPU quota (step 2) and create the stack again. |
| `/healthz` never answers | Read the boot log: `aws ec2 get-console-output --region <region> --instance-id <InstanceId> --latest --output text`. `boot setup failed` means a download or the key read failed; check the subnet reaches the internet. |
| `/healthz` answers from the instance but not from Tessary | Tessary's security group must be the one passed as `TessarySecurityGroupId`, and both must be in the same VPC. |
| The instance stops right after it starts | serve.py failed and stopped it. Start it and read the service log through Session Manager: `journalctl -u groundedness`. |
| Tessary's backend log shows `401` on sweeps | The key in Tessary's `.env` differs from the secret. Hand over the lines from step 7 again. |
| Tessary shows "No scores since" for more than 2 hours | Follow [Restart](#restart). |
