<?php

declare(strict_types=1);

namespace MockServer;

/**
 * A per-step response assertion for a load scenario step (the load equivalent
 * of a k6 check): reads a value from the step's response and compares it with
 * {@code value}.
 *
 * {@code source} is STATUS (the status code), HEADER (the header named by
 * {@see LoadCheck::headerName()}) or BODY_JSONPATH (the JSONPath set by
 * {@see LoadCheck::jsonPath()}). {@code comparator} is EQUALS, NOT_EQUALS,
 * CONTAINS, MATCHES (full-match regex), GT, LT, GTE or LTE (numeric).
 *
 * A failing check never fails the request; failures are counted and feed the
 * CHECK_FAILURE_RATE threshold. Attach checks with the {@code checks} argument of
 * {@see LoadScenario::addStep()}.
 *
 * @example
 *   LoadCheck::status('EQUALS', '200');
 *   LoadCheck::header('Content-Type', 'CONTAINS', 'application/json');
 *   LoadCheck::bodyJsonPath('$.status', 'EQUALS', 'CONFIRMED');
 *   LoadCheck::of('STATUS', 'LT', '500');
 */
class LoadCheck implements \JsonSerializable
{
    private string $source;
    private string $comparator;
    private ?string $value;
    private ?string $headerName = null;
    private ?string $jsonPath = null;

    private function __construct(string $source, string $comparator, ?string $value)
    {
        $this->source = strtoupper($source);
        $this->comparator = strtoupper($comparator);
        $this->value = $value;
    }

    /**
     * Build a check from its source, comparator and expected value. Set the
     * header name or JSONPath with {@see headerName()} / {@see jsonPath()}.
     *
     * @param string $source STATUS, HEADER or BODY_JSONPATH
     * @param string $comparator EQUALS, NOT_EQUALS, CONTAINS, MATCHES, GT, LT, GTE or LTE
     * @param string|null $value the expected value the observed value is compared with
     */
    public static function of(string $source, string $comparator, ?string $value = null): self
    {
        return new self($source, $comparator, $value);
    }

    /**
     * A check on the response status code.
     */
    public static function status(string $comparator, string $value): self
    {
        return new self('STATUS', $comparator, $value);
    }

    /**
     * A check on the value of response header {@code $headerName}.
     */
    public static function header(string $headerName, string $comparator, string $value): self
    {
        return (new self('HEADER', $comparator, $value))->headerName($headerName);
    }

    /**
     * A check on the value {@code $jsonPath} selects from the response body.
     */
    public static function bodyJsonPath(string $jsonPath, string $comparator, string $value): self
    {
        return (new self('BODY_JSONPATH', $comparator, $value))->jsonPath($jsonPath);
    }

    /**
     * Read a check from a decoded JSON object, such as one in a scenario
     * definition the server returns. Keys the check does not model (the server
     * echoes a computed {@code valid} flag, for example) are ignored.
     *
     * @param array<string, mixed> $data
     */
    public static function fromArray(array $data): self
    {
        $check = new self(
            self::stringOrNull($data['source'] ?? null) ?? '',
            self::stringOrNull($data['comparator'] ?? null) ?? '',
            self::stringOrNull($data['value'] ?? null),
        );
        $check->headerName = self::stringOrNull($data['headerName'] ?? null);
        $check->jsonPath = self::stringOrNull($data['jsonPath'] ?? null);

        return $check;
    }

    private static function stringOrNull(mixed $value): ?string
    {
        if ($value === null) {
            return null;
        }
        if (is_string($value)) {
            return $value;
        }
        if (is_int($value) || is_float($value)) {
            return (string) $value;
        }
        return null;
    }

    /**
     * Set the response header to read (when the source is HEADER).
     */
    public function headerName(string $headerName): self
    {
        $this->headerName = $headerName;

        return $this;
    }

    /**
     * Set the JSONPath evaluated over the response body (when the source is BODY_JSONPATH).
     */
    public function jsonPath(string $jsonPath): self
    {
        $this->jsonPath = $jsonPath;

        return $this;
    }

    /**
     * Set the expected value the observed value is compared with.
     */
    public function value(string $value): self
    {
        $this->value = $value;

        return $this;
    }

    public function getSource(): string
    {
        return $this->source;
    }

    public function getComparator(): string
    {
        return $this->comparator;
    }

    public function getValue(): ?string
    {
        return $this->value;
    }

    public function getHeaderName(): ?string
    {
        return $this->headerName;
    }

    public function getJsonPath(): ?string
    {
        return $this->jsonPath;
    }

    /**
     * @return array<string, mixed>
     */
    public function jsonSerialize(): array
    {
        return $this->toArray();
    }

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $result = ['source' => $this->source];
        if ($this->headerName !== null) {
            $result['headerName'] = $this->headerName;
        }
        if ($this->jsonPath !== null) {
            $result['jsonPath'] = $this->jsonPath;
        }
        $result['comparator'] = $this->comparator;
        if ($this->value !== null) {
            $result['value'] = $this->value;
        }

        return $result;
    }
}
