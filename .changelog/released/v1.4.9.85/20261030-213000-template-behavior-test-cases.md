<!-- pr: template-behavior-test-cases -->

- 「测试用例」模板代码化：函数签名提取（含跨行签名/泛型参数不拆分/Python 默认值类型推断）
- 类型 → 等价类表自动推导：数字边界（0/1/-1/MAX）、字符串边界（空串/单字符/超长）、集合边界（空/单元素）、浮点特值（NaN/Infinity）、空安全路径
- 测试矩阵骨架先行（正常/边界/异常三路径 × 每参数），框架约定库随附录注入（JUnit4/5、Kotest、pytest、Jest、go test 六套）
- suspend 函数自动提示 runTest 驱动，默认值参数自动补「省略入参走默认值」用例
