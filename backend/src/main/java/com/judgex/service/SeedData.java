package com.judgex.service;

import com.judgex.auth.PasswordHasher;
import com.judgex.repo.ProblemMapper;
import com.judgex.repo.UserMapper;
import com.judgex.web.dto.Dto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/** Seeds a demo tenant on first boot. Idempotent: it only writes when tables are empty. */
@Component
public class SeedData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    private final UserMapper users;
    private final ProblemMapper problems;

    public SeedData(UserMapper users, ProblemMapper problems) {
        this.users = users;
        this.problems = problems;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() == 0) {
            users.insert("admin", PasswordHasher.hash("admin123"), "平台管理员", "ADMIN");
            users.insert("student", PasswordHasher.hash("student123"), "林同学", "USER");
            users.insert("chen", PasswordHasher.hash("student123"), "陈同学", "USER");
            log.info("seeded demo users: admin/admin123, student/student123");
        }
        if (problems.count() == 0) {
            seedProblems();
            log.info("seeded demo problems");
        }
    }

    private void seedProblems() {
        addProblem(new Dto.ProblemUpsertRequest(
                "a-plus-b", "A + B（热身题）", "EASY", "入门,输入输出",
                """
                ## 题目描述

                给定两个整数 `a` 和 `b`，输出它们的和。

                这道题只有一个目的：确认你的环境和提交链路是通的。

                ## 输入格式

                一行两个整数 `a b`，用空格分隔，`|a|, |b| <= 10^9`。

                ## 输出格式

                一行一个整数，表示 `a + b`。

                ## 样例

                输入：`1 2`
                输出：`3`
                """, 2000, 256, true),
                List.of(
                        c(1, "1 2", "3"),
                        c(2, "0 0", "0"),
                        c(3, "-5 5", "0"),
                        c(4, "1000000000 1000000000", "2000000000"),
                        c(5, "-1000000000 -1", "-1000000001")
                ));

        addProblem(new Dto.ProblemUpsertRequest(
                "reverse-string", "字符串反转", "EASY", "字符串,双指针",
                """
                ## 题目描述

                给定一个只包含可见 ASCII 字符的字符串，输出它反转后的结果。

                ## 输入格式

                一行字符串 `s`，长度不超过 1000，不含空格。

                ## 输出格式

                一行，反转后的字符串。

                ## 样例

                输入：`judgex`
                输出：`xegduj`

                ## 提示

                可以用双指针原地交换，也可以新建一个字符串。
                """, 2000, 256, true),
                List.of(
                        c(1, "judgex", "xegduj"),
                        c(2, "a", "a"),
                        c(3, "ab", "ba"),
                        c(4, "hello", "olleh"),
                        c(5, "123456789", "987654321")
                ));

        addProblem(new Dto.ProblemUpsertRequest(
                "max-subarray", "最大子段和", "MEDIUM", "动态规划,数组",
                """
                ## 题目描述

                给定长度为 `n` 的整数数组，找到一个连续子数组，使它的元素和最大，输出这个最大和。

                子数组至少包含一个元素。

                ## 输入格式

                第一行一个整数 `n`（`1 <= n <= 100000`）。

                第二行 `n` 个整数，用空格分隔，`|a[i]| <= 10^4`。

                ## 输出格式

                一行一个整数，表示最大子段和。

                ## 样例

                输入：

                ```
                8
                -2 1 -3 4 -1 2 1 -5
                ```

                输出：`6`

                ## 提示

                Kadane 算法可以做到 O(n)。注意答案可能是负数。
                """, 2000, 256, true),
                List.of(
                        c(1, "8\n-2 1 -3 4 -1 2 1 -5", "6"),
                        c(2, "1\n-7", "-7"),
                        c(3, "5\n1 2 3 4 5", "15"),
                        c(4, "3\n-1 -2 -3", "-1"),
                        c(5, "6\n2 -1 2 -1 2 -1", "4")
                ));

        addProblem(new Dto.ProblemUpsertRequest(
                "fibonacci", "斐波那契数列", "EASY", "递推,记忆化",
                """
                ## 题目描述

                求斐波那契数列第 `n` 项。定义 `F(1) = 1`，`F(2) = 1`，`F(n) = F(n-1) + F(n-2)`。

                ## 输入格式

                一行一个整数 `n`（`1 <= n <= 90`）。

                ## 输出格式

                一行一个整数，表示 `F(n)`。

                ## 样例

                输入：`10`
                输出：`55`

                ## 提示

                `n` 最大到 90，结果超过 32 位整数范围，请使用 64 位整数。
                """, 2000, 256, true),
                List.of(
                        c(1, "10", "55"),
                        c(2, "1", "1"),
                        c(3, "2", "1"),
                        c(4, "50", "12586269025"),
                        c(5, "90", "2880067194370816120")
                ));

        addProblem(new Dto.ProblemUpsertRequest(
                "bracket-match", "括号匹配", "MEDIUM", "栈,字符串",
                """
                ## 题目描述

                给定一个只包含 `()[]{}` 的字符串，判断括号是否合法配对。

                ## 输入格式

                一行字符串 `s`，长度不超过 10^5。

                ## 输出格式

                合法输出 `YES`，否则输出 `NO`。

                ## 样例

                输入：`{[()]}`
                输出：`YES`

                输入：`(]`
                输出：`NO`

                ## 提示

                经典栈应用：左括号入栈，右括号与栈顶比较。
                """, 2000, 256, true),
                List.of(
                        c(1, "{[()]}", "YES"),
                        c(2, "(]", "NO"),
                        c(3, "()[]{}", "YES"),
                        c(4, "(((", "NO"),
                        c(5, "]", "NO")
                ));

        addProblem(new Dto.ProblemUpsertRequest(
                "kth-largest", "第 K 大元素", "HARD", "堆,快速选择",
                """
                ## 题目描述

                给定 `n` 个整数，输出其中第 `k` 大的元素（重复元素按位置计数）。

                ## 输入格式

                第一行两个整数 `n` 和 `k`（`1 <= k <= n <= 100000`）。

                第二行 `n` 个整数，用空格分隔。

                ## 输出格式

                一行一个整数。

                ## 样例

                输入：

                ```
                6 2
                3 2 1 5 6 4
                ```

                输出：`5`

                ## 提示

                小顶堆维护 k 个元素是 O(n log k)；快速选择期望 O(n)。
                """, 3000, 256, true),
                List.of(
                        c(1, "6 2\n3 2 1 5 6 4", "5"),
                        c(2, "1 1\n42", "42"),
                        c(3, "5 5\n5 4 3 2 1", "1"),
                        c(4, "5 1\n5 4 3 2 1", "5"),
                        c(5, "7 3\n3 3 3 1 1 2 2", "2")
                ));
    }

    private void addProblem(Dto.ProblemUpsertRequest problem, List<Dto.TestCaseUpsertRequest> cases) {
        long id = problems.insert(problem);
        problems.replaceCases(id, cases);
    }

    private static Dto.TestCaseUpsertRequest c(int ordinal, String input, String output) {
        return new Dto.TestCaseUpsertRequest(ordinal, input, output, 20, ordinal > 2);
    }
}
