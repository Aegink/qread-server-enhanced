package web.util.admin

import java.util.Arrays

object AdminPasswordTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val console = System.console()
        val passwordChars = console?.readPassword("请输入管理员密码（至少 12 位）: ")
            ?: readPasswordFromStdin()
        try {
            require(passwordChars.size >= 12) { "管理员密码至少需要 12 位" }
            val confirmation = console?.readPassword("请再次输入管理员密码: ")
                ?: readPasswordFromStdin()
            try {
                require(passwordChars.contentEquals(confirmation)) { "两次输入的密码不一致" }
                println(passsign(passwordChars.concatToString()))
            } finally {
                Arrays.fill(confirmation, '\u0000')
            }
        } finally {
            Arrays.fill(passwordChars, '\u0000')
        }
    }

    private fun readPasswordFromStdin(): CharArray {
        return readlnOrNull()?.toCharArray()
            ?: throw IllegalArgumentException("未读取到管理员密码")
    }
}
