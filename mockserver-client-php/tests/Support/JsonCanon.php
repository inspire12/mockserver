<?php

declare(strict_types=1);

namespace MockServer\Tests\Support;

/**
 * Canonical form of a decoded JSON value for structural equality: JSON object
 * keys are sorted (recursively), list order is kept, so assertSame on two canon
 * values compares content and types but not object key order.
 */
final class JsonCanon
{
    public static function canon(mixed $value): mixed
    {
        if (!is_array($value)) {
            return $value;
        }
        $out = array_map(static fn (mixed $v): mixed => self::canon($v), $value);
        if (!array_is_list($out)) {
            ksort($out);
        }
        return $out;
    }

    /**
     * Decode a JSON document (objects as associative arrays) and canonicalise it.
     */
    public static function decode(string $json): mixed
    {
        return self::canon(json_decode($json, true, 512, JSON_THROW_ON_ERROR));
    }
}
