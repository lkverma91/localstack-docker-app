#!/bin/bash
set -e

echo "=== Initializing LocalStack resources ==="

# Create S3 bucket for user profile pictures
awslocal s3 mb s3://user-profiles
echo "Created S3 bucket: user-profiles"

# Store application secrets in SSM Parameter Store
awslocal ssm put-parameter \
  --name "/app/jwt/secret" \
  --value "b3BlbnNlc2FtZS1sb2NhbHN0YWNrLWp3dC1zZWNyZXQta2V5LXRoYXQtaXMtbG9uZy1lbm91Z2gtZm9yLUhTNTEy" \
  --type SecureString \
  --overwrite

awslocal ssm put-parameter \
  --name "/app/jwt/access-token-expiry-ms" \
  --value "900000" \
  --type String \
  --overwrite

awslocal ssm put-parameter \
  --name "/app/jwt/refresh-token-expiry-ms" \
  --value "86400000" \
  --type String \
  --overwrite

echo "Created SSM parameters"

# Verify SES email identity (for future email verification)
awslocal ses verify-email-identity --email-address noreply@authapp.local
echo "Verified SES email identity: noreply@authapp.local"

echo "=== LocalStack initialization complete ==="
