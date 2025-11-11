$version: "2.0"

namespace com.dwolla.aws.secretsManager

use smithy4s.meta#only
use com.dwolla.tracing.smithy#traceable

apply com.amazonaws.secretsmanager#GetSecretValue @only

apply com.amazonaws.secretsmanager#CreatedDateType @traceable
apply com.amazonaws.secretsmanager#ErrorMessage @traceable
apply com.amazonaws.secretsmanager#SecretARNType @traceable
apply com.amazonaws.secretsmanager#SecretBinaryType @traceable
apply com.amazonaws.secretsmanager#SecretIdType @traceable
apply com.amazonaws.secretsmanager#SecretNameType @traceable
apply com.amazonaws.secretsmanager#SecretStringType @traceable(redacted: "redacted SecretString")
apply com.amazonaws.secretsmanager#SecretVersionIdType @traceable
apply com.amazonaws.secretsmanager#SecretVersionStageType @traceable
apply com.amazonaws.secretsmanager#SecretVersionStagesType @traceable
