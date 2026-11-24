-- Function-style judging: the submission is only the solution class, the judge wraps it
-- with a per-language harness that parses one line of JSON-ish arguments and prints the
-- return value. Existing problems keep the default (STDIN) and are judged exactly as before.

ALTER TABLE problems
    ADD COLUMN judge_mode VARCHAR(16) NOT NULL DEFAULT 'STDIN' AFTER published,
    ADD COLUMN signature  VARCHAR(200) NULL AFTER judge_mode;

-- ---------------------------------------------------------------- two-sum
INSERT INTO problems (slug, title, difficulty, tags, statement, time_limit_ms, memory_limit_mb, published, judge_mode, signature)
VALUES ('two-sum', '两数之和', 'EASY', '数组,哈希表',
        '给定一个整数数组 `nums` 和一个整数目标值 `target`，请你在该数组中找出**和为目标值**的那两个整数，并返回它们的数组下标。

你可以假设每种输入只会对应一个答案，且同一个元素不能重复使用。返回值请按下标升序排列。

**函数签名**

```java
int[] twoSum(int[] nums, int target)
```

**示例**

```
输入：nums = [2,7,11,15], target = 9
输出：[0,1]
解释：nums[0] + nums[1] = 2 + 7 = 9
```

**提示**

- 2 ≤ nums.length ≤ 10⁴
- -10⁹ ≤ nums[i] ≤ 10⁹
- 只会存在一个有效答案',
        1000, 256, 1, 'FUNCTION', 'int[] twoSum(int[] nums, int target)');
SET @p1 = LAST_INSERT_ID();

INSERT INTO test_cases (problem_id, ordinal, input, expected_output, points, hidden) VALUES
    (@p1, 1, '[2,7,11,15], 9', '[0,1]', 20, 0),
    (@p1, 2, '[3,2,4], 6', '[1,2]', 20, 0),
    (@p1, 3, '[3,3], 6', '[0,1]', 20, 1),
    (@p1, 4, '[-3,4,3,90], 0', '[0,2]', 20, 1),
    (@p1, 5, '[1,5,9,12,20], 21', '[2,3]', 20, 1);

-- ---------------------------------------------------------- valid parentheses
INSERT INTO problems (slug, title, difficulty, tags, statement, time_limit_ms, memory_limit_mb, published, judge_mode, signature)
VALUES ('valid-parentheses', '有效的括号', 'EASY', '栈,字符串',
        '给定一个只包括 `(`、`)`、`{`、`}`、`[`、`]` 的字符串 `s`，判断字符串是否有效。

有效字符串需满足：左括号必须用相同类型的右括号闭合，且必须以正确的顺序闭合。

**函数签名**

```java
boolean isValid(String s)
```

**示例**

```
输入：s = "()[]{}"
输出：true

输入：s = "([)]"
输出：false
```

**提示**

- 1 ≤ s.length ≤ 10⁴
- s 仅由括号字符组成',
        1000, 256, 1, 'FUNCTION', 'boolean isValid(String s)');
SET @p2 = LAST_INSERT_ID();

INSERT INTO test_cases (problem_id, ordinal, input, expected_output, points, hidden) VALUES
    (@p2, 1, '"()"', 'true', 20, 0),
    (@p2, 2, '"()[]{}"', 'true', 20, 0),
    (@p2, 3, '"(]"', 'false', 20, 1),
    (@p2, 4, '"([)]"', 'false', 20, 1),
    (@p2, 5, '"{[]}"', 'true', 20, 1);
