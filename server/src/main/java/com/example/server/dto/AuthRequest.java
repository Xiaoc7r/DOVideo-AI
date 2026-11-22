package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录/注册共用的请求体。
 *
 * <p>两个场景的校验强度不同，因此用校验分组区分：注册要求密码强度（≥8 位）， 登录只校验非空与长度上限。这样历史遗留的短密码账号仍能登录， 由 {@code
 * AuthService.login} 走原有的“校验通过后升级哈希”逻辑完成迁移； 若把注册强度校验施加到登录上，会把这些老账号直接挡在门外。
 */
public record AuthRequest(
    @NotBlank(
            message = "账号不能为空",
            groups = {Login.class, Register.class})
        @Size(min = 3, max = 32, message = "账号需为 3-32 位", groups = Register.class)
        @Size(max = 32, message = "账号不能超过 32 位", groups = Login.class)
        String username,
    @NotBlank(
            message = "密码不能为空",
            groups = {Login.class, Register.class})
        @Size(
            max = 128,
            message = "密码不能超过 128 位",
            groups = {Login.class, Register.class})
        @Size(min = 8, message = "密码需至少 8 位", groups = Register.class)
        String password,
    @Size(max = 50, message = "昵称不能超过 50 个字符", groups = Register.class) String nickname) {
  /** 登录校验分组：只做非空与长度上限校验，不做强度要求。 */
  public interface Login {}

  /** 注册校验分组：包含账号格式与密码强度要求。 */
  public interface Register {}
}
