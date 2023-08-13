package com.hmdp.service.impl;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import javax.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.不符合返回错误信息
            return Result.fail("手机号错误");
        }
        //3.符合生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到session
        session.setAttribute("code", code);
        //5.发个验证码
        log.debug("Received code: " + code);
        return Result.ok();
    }
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        //1.校验手机号校验验证码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.不符合返回错误信息
            return Result.fail("手机号错误");
        }
        //2.验证码校验
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        //3.不一致 报错误
        if (cacheCode == null || cacheCode.toString().equals(code)) {
            return Result.fail("验证码错误");
        }
        //4.一致 根据手机号查询数据库 select * from tb_user where phone=？
        User user = query().eq("phone", phone).one();
        //5.存在 登录
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        //6.不存在创建新用户信息
        //7.保存用户信息到session
        session.setAttribute("user",user);
        return Result.ok();
    }

    public User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user"+RandomUtil.randomString(6));

        //保存用户
        save(user);
        return user;
    }
}
