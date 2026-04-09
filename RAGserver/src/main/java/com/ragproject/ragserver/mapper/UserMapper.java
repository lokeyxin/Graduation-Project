package com.ragproject.ragserver.mapper;

import com.ragproject.ragserver.model.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper {
    User findByUsername(@Param("username") String username);

    User findById(@Param("userId") Long userId);

    List<User> findAllActiveUsers();

    int insert(User user);
}
