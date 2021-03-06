package kiml.backend

import asmble.ast.Node
import asmble.ast.Node.*
import asmble.ast.Node.Type.Value
import asmble.io.AstToBinary
import asmble.io.AstToSExpr
import asmble.io.ByteWriter
import kiml.frontend.*
import java.io.ByteArrayOutputStream
import java.io.File

class Locals(vararg params: Value) {
    private var list: MutableList<Value> = params.toMutableList()
    private var nParams: Int = params.size

    fun register(type: Value): Int = list.size.also { list.add(type) }

    // All the locals the user registered
    fun getLocals(): List<Value> = list.drop(nParams)
}

class Codegen {
    val globals: MutableList<Pair<String, Node.Global>> = mutableListOf()
    val types: MutableList<Pair<String, Node.Type.Func>> = mutableListOf()
    val global_funcs: MutableList<Pair<String, Func>> = mutableListOf()
    val table_funcs: MutableList<String> = mutableListOf()

    fun define_builtin_binary(name: String, instr: Instr) {
        val inner = make_fn2("$name\$inner", Value.I32, Value.I32) { _, x, y ->
            listOf(
                Instr.GetLocal(x),
                Instr.GetLocal(y),
                instr
            )
        }

        make_fn1(name, Value.I32) { _, arg_pointer ->
            listOf(
                Instr.GetLocal(arg_pointer),
                Instr.I32Load(2, 0),
                Instr.GetLocal(arg_pointer),
                Instr.I32Const(4),
                Instr.I32Add,
                Instr.I32Load(2, 0),
                Instr.Call(inner)
            )
        }
        register_table_func(name)
    }

    fun init_rts() {
        val watermark =
            register_global("watermark", Node.Global(Node.Type.Global(Value.I32, true), listOf(Instr.I32Const(0))))
        val allocate = make_fn1("allocate", Value.I32) { _, bytes ->
            listOf(
                Instr.GetGlobal(watermark),
                Instr.GetGlobal(watermark),
                Instr.GetLocal(bytes),
                Instr.I32Add,
                Instr.SetGlobal(watermark)
            )
        }

        make_fn2("make_closure", Value.I32, Value.I32) { locals, arity, code_pointer ->
            val closureStart = locals.register(Value.I32)
            listOf(
                Instr.I32Const(4),
                Instr.GetLocal(arity),
                Instr.I32Mul,
                Instr.I32Const(8), // arity + applied + code_pointer
                Instr.I32Add,
                Instr.Call(allocate),
                Instr.TeeLocal(closureStart),
                Instr.GetLocal(arity),
                Instr.I32Store16(1, 0),
                Instr.GetLocal(closureStart),
                Instr.I32Const(4),
                Instr.I32Add,
                Instr.GetLocal(code_pointer),
                Instr.I32Store(2, 0),
                Instr.GetLocal(closureStart)
            )
        }


//  (func $copy_closure (param $closure i32) (result i32)
        val copy_closure = make_fn1(
            "copy_closure",
            Value.I32
        ) { locals, closure ->
            //    (local $new_closure i32) (local $size i32) (local $arity i32) (local $x i32)
            val new_closure = locals.register(Value.I32)
            val size = locals.register(Value.I32)
            val arity = locals.register(Value.I32)
            val x = locals.register(Value.I32)
            listOf(
//    local.get $closure
                Instr.GetLocal(closure),
//    i32.load16_s
                Instr.I32Load16S(1, 0),
//    local.tee $arity
                Instr.TeeLocal(arity),
//    i32.const 4
                Instr.I32Const(4),
//    i32.mul
                Instr.I32Mul,
//    i32.const 8
                Instr.I32Const(8),
//    i32.add
                Instr.I32Add,
//    local.tee $size
                Instr.TeeLocal(size),
//    call $allocate
                Instr.Call(allocate),
//    local.set $new_closure
                Instr.SetLocal(new_closure),
//    i32.const 0
                Instr.I32Const(0),
//    local.set $x
                Instr.SetLocal(x),
//    block  ;; label = @1
                Instr.Block(null),
//      loop  ;; label = @2
                Instr.Loop(null),
//        local.get $x
                Instr.GetLocal(x),
//        local.get $size
                Instr.GetLocal(size),
//        i32.ge_s
                Instr.I32GeS,
//        br_if 1 (;@1;)
                Instr.BrIf(1),
//        local.get $x
                Instr.GetLocal(x),
//        local.get $new_closure
                Instr.GetLocal(new_closure),
//        i32.add
                Instr.I32Add,
//        local.get $x
                Instr.GetLocal(x),
//        local.get $closure
                Instr.GetLocal(closure),
//        i32.add
                Instr.I32Add,
//        i32.load
                Instr.I32Load(2, 0),
//        i32.store
                Instr.I32Store(2, 0),
//        i32.const 4
                Instr.I32Const(4),
//        local.get $x
                Instr.GetLocal(x),
//        i32.add
                Instr.I32Add,
//        local.set $x
                Instr.SetLocal(x),
//        br 0 (;@2;)
                Instr.Br(0),
//      end
                Instr.End,
//    end
                Instr.End,
//    local.get $new_closure)
                Instr.GetLocal(new_closure)
            )
        }

        make_fn2(
            "apply_closure",
            Value.I32,
            Value.I32
        ) { locals, closure_start, argument ->
            val arity = locals.register(Value.I32)
            val applied = locals.register(Value.I32)
            val codePointer = locals.register(Value.I32)
            listOf(
                Instr.GetLocal(closure_start),
                Instr.Call(copy_closure),
                Instr.TeeLocal(closure_start),
                Instr.I32Load16S(1, 0),
                Instr.SetLocal(arity),

                Instr.GetLocal(closure_start),
                Instr.I32Const(2),
                Instr.I32Add,
                Instr.I32Load16S(1, 0),
                Instr.SetLocal(applied),

                Instr.GetLocal(closure_start),
                Instr.I32Const(4),
                Instr.I32Add,
                Instr.I32Load(2, 0),
                Instr.SetLocal(codePointer),

                // Write the argument
                Instr.I32Const(4),
                Instr.GetLocal(applied),
                Instr.I32Mul,
                Instr.I32Const(8),
                Instr.I32Add,
                Instr.GetLocal(closure_start),
                Instr.I32Add,
                Instr.GetLocal(argument),
                Instr.I32Store(2, 0),

                Instr.GetLocal(applied),
                Instr.I32Const(1),
                Instr.I32Add,
                Instr.GetLocal(arity),
                Instr.I32LtS,

                Instr.If(Value.I32),
                // Location of applied
                Instr.GetLocal(closure_start),
                Instr.I32Const(2),
                Instr.I32Add,
                // applied++
                Instr.GetLocal(applied),
                Instr.I32Const(1),
                Instr.I32Add,
                Instr.I32Store16(1, 0),

                // Not all arguments were applied so we return the closure
                Instr.GetLocal(closure_start),
                Instr.Else,
                // Location of arguments
                Instr.GetLocal(closure_start),
                Instr.I32Const(8),
                Instr.I32Add,

                Instr.GetLocal(codePointer),
                Instr.CallIndirect(
                    register_type("int_to_int", Node.Type.Func(listOf(Value.I32), Value.I32)),
                    false
                ),
                Instr.End
            )
        }

        // Pack Layout:
        // | tag 2byte | arity 2byte | values n*4byte |

        make_fn2("make_pack", Value.I32, Value.I32) { locals, tag, arity ->
            val pack_start = locals.register(Value.I32)
            listOf(
                // allocate 4*arity + 4
                Instr.GetLocal(arity),
                Instr.I32Const(4),
                Instr.I32Mul,
                Instr.I32Const(4),
                Instr.I32Add,
                Instr.Call(allocate),
                // Write tag and arity
                Instr.TeeLocal(pack_start),
                Instr.GetLocal(tag),
                Instr.I32Store16(1, 0),
                Instr.GetLocal(pack_start),
                Instr.I32Const(2),
                Instr.I32Add,
                Instr.GetLocal(arity),
                Instr.I32Store16(1, 0),
                Instr.GetLocal(pack_start)
            )
        }

        make_fnN("write_pack_field", listOf(Value.I32, Value.I32, Value.I32)) { _, args ->
            val pack = args[0]
            val offset = args[1]
            val field = args[2]
            listOf(
                Instr.GetLocal(pack),
                Instr.I32Const(4),
                Instr.I32Add,
                Instr.GetLocal(offset),
                Instr.I32Const(4),
                Instr.I32Mul,
                Instr.I32Add,
                Instr.GetLocal(field),
                Instr.I32Store(2, 0),
                // After writing the field put the pack pointer back on the stack
                Instr.GetLocal(pack)
            )
        }

        make_fn2("read_pack_field", Value.I32, Value.I32) { _, pack, offset ->
            listOf(
                Instr.GetLocal(pack),
                Instr.I32Const(4),
                Instr.I32Add,
                Instr.GetLocal(offset),
                Instr.I32Const(4),
                Instr.I32Mul,
                Instr.I32Add,
                Instr.I32Load(2, 0)
            )
        }

        make_fn1("read_pack_tag", Value.I32) { _, pack ->
            listOf(
                Instr.GetLocal(pack),
                Instr.I32Load16S(1, 0)
            )
        }

        define_builtin_binary("add", Instr.I32Add)
        define_builtin_binary("eq_int", Instr.I32Eq)
        define_builtin_binary("sub", Instr.I32Sub)
        define_builtin_binary("div", Instr.I32DivS)
    }

    fun get_arity(funcName: String): Int =
        global_funcs[func_index("$funcName\$inner")]
            .second
            .type
            .params
            .size


    fun register_global_func(name: String, func: Func): Int =
        global_funcs.size.also { global_funcs += name to func }

    fun register_table_func(name: String): Int =
        table_funcs.size.also { table_funcs.add(name) }

    fun register_global(name: String, global: Node.Global): Int =
        globals.size.also { globals.add(name to global) }

    fun register_type(name: String, typ: Node.Type.Func): Int =
        types.size.also { types.add(name to typ) }

    private fun dummyFunc(arity: Int) =
        Func(Type.Func((0 until arity).map { Value.I32 }, null), listOf(), listOf(Instr.Unreachable))

    private fun make_fn1(name: String, arg: Value, body: (Locals, Int) -> List<Instr>): Int {
        val self = register_global_func(name, dummyFunc(1))
        val locals = Locals(arg)
        val instrs = body(locals, 0)
        val new_func = Func(Node.Type.Func(listOf(arg), Value.I32), locals.getLocals(), instrs)
        global_funcs[self] = name to new_func
        return self
    }

    private fun make_fn2(
        name: String,
        arg1: Value,
        arg2: Value,
        body: (Locals, Int, Int) -> List<Instr>
    ): Int {
        val self = register_global_func(name, dummyFunc(2))
        val locals = Locals(arg1, arg2)
        val instrs = body(locals, 0, 1)
        val new_func =
            Func(Node.Type.Func(listOf(arg1, arg2), Value.I32), locals.getLocals(), instrs)
        global_funcs[self] = name to new_func
        return self
    }

    private fun make_fnN(
        name: String,
        args: List<Value>,
        body: (Locals, List<Int>) -> List<Instr>
    ): Int {
        val self = register_global_func(name, dummyFunc(args.size))
        val locals = Locals(*args.toTypedArray())
        val instrs = body(locals, args.indices.toList())
        val new_func =
            Func(Node.Type.Func(args, Value.I32), locals.getLocals(), instrs)
        global_funcs[self] = name to new_func
        return self
    }

    fun func_index(name: String): Int {
        val res = global_funcs.indexOfFirst { name == it.first }
        return if (res == -1) throw Exception("Failed to find function index for: $name") else res
    }

    fun table_func_index(name: String): Int {
        val res = table_funcs.indexOfFirst { name == it }
        return if (res == -1) throw Exception("Failed to find function index for: $name") else res
    }

    fun compile_prog(prog: List<IR.Declaration>): Module {
        prog.forEach { decl ->
            compile_decl(decl)
        }
        return Module(
            memories = listOf(Node.Type.Memory(Node.ResizableLimits(65535, null))),
            globals = globals.map { it.second },
            tables = listOf(Node.Type.Table(Node.ElemType.ANYFUNC, Node.ResizableLimits(table_funcs.size, null))),
            elems = listOf(Node.Elem(0, listOf(Instr.I32Const(0)), table_funcs.map { func_index(it) })),
            exports = global_funcs.mapIndexed { ix, (name, _) -> Export(name, ExternalKind.FUNCTION, ix) },
            funcs = global_funcs.map { it.second },
            types = types.map { it.second }
        )
    }

    fun compile_decl(decl: IR.Declaration) {
        val arity = decl.arguments.size
        println("Compiling Decl: ${decl.name.v} @ $arity")
        register_table_func(decl.name.v)

        val inner = make_fnN(decl.name.v + "\$inner", decl.arguments.map { Value.I32 }) { locals, params ->
            println("${decl.pretty()} => $params")
            compile_expr(locals, decl.body.instantiate(params.map { IR.Expression.GetLocal(it) }))
        }
        make_fn1(decl.name.v, Value.I32) { _, arg_pointer ->
            val instrs = mutableListOf<Instr>()
            for (i in 0 until arity) {
                instrs.addAll(
                    listOf(
                        Instr.GetLocal(arg_pointer),
                        Instr.I32Const(4 * i),
                        Instr.I32Add,
                        Instr.I32Load(2, 0)
                    )
                )
            }
            instrs.add(Instr.Call(inner))
            instrs
        }

    }

    private fun compile_expr(locals: Locals, expr: IR.Expression): List<Instr> {
        return when (expr) {
            is IR.Expression.Int -> listOf(Instr.I32Const(expr.int))
            is IR.Expression.Bool -> listOf(Instr.I32Const(if (expr.bool) 1 else 0))
            is IR.Expression.Var -> when (expr.name) {
                is LNName.Bound -> throw Exception("Should've been instantiated: ${expr.name}")
                is LNName.Free -> {
                    val arity = get_arity(expr.name.v.v)
                    listOf<Instr>(
                        Instr.I32Const(arity),
                        Instr.I32Const(table_func_index(expr.name.v.v)),
                        Instr.Call(func_index("make_closure"))
                    )
                }
            }
            is IR.Expression.Application -> {
                // Optimization: If the called function is a known function with the same arity as the number of given
                // arguments we can call its stack based variant rather than building up a closure in memory
                val (fn, args) = expr.unfoldApps()
                if (fn is IR.Expression.Var && fn.name is LNName.Free && get_arity(fn.name.v.v) == args.size) {
                    args.flatMap { compile_expr(locals, it) } + Instr.Call(func_index("${fn.name.v.v}\$inner"))
                } else {
                    compile_expr(locals, expr.func) +
                            compile_expr(locals, expr.argument) +
                            listOf(Instr.Call(func_index("apply_closure")))
                }
            }
            is IR.Expression.Pack -> {
                listOf<Instr>(
                    Instr.I32Const(expr.tag),
                    Instr.I32Const(expr.values.size),
                    Instr.Call(func_index("make_pack"))
                ) + expr.values.withIndex().flatMap { (ix, field) ->
                    listOf(Instr.I32Const(ix)) +
                            compile_expr(locals, field) +
                            listOf(Instr.Call(func_index("write_pack_field")))
                }
            }
            is IR.Expression.Match -> {
                val scrutinee = locals.register(Value.I32)
                val tag = locals.register(Value.I32)
                compile_expr(locals, expr.scrutinee) +
                        listOf<Instr>(
                            Instr.TeeLocal(scrutinee),
                            Instr.Call(func_index("read_pack_tag")),
                            Instr.SetLocal(tag)
                        ) + expr.cases.fold(listOf<Instr>(Instr.Unreachable)) { acc, case ->
                    compile_case(locals, scrutinee, tag, case, acc)
                }

            }
            is IR.Expression.If ->
                compile_expr(locals, expr.condition) +
                        listOf(Instr.If(Value.I32)) +
                        compile_expr(locals, expr.thenCase) +
                        listOf(Instr.Else) +
                        compile_expr(locals, expr.elseCase) +
                        listOf(Instr.End)
            is IR.Expression.Let -> {
                val binder = locals.register(Value.I32)
                val body = expr.body.instantiate(listOf(IR.Expression.GetLocal(binder)))
                compile_expr(locals, expr.expr) +
                        listOf(Instr.SetLocal(binder)) +
                        compile_expr(locals, body)
            }
            is IR.Expression.GetLocal -> listOf(Instr.GetLocal(expr.ix))
        }
    }

    private fun compile_case(locals: Locals, scrutinee: Int, tag: Int, case: IR.Case, cont: List<Instr>): List<Instr> {
        val binders = case.binders.map { locals.register(Value.I32) }
        return listOf<Instr>(
            Instr.GetLocal(tag),
            Instr.I32Const(case.tag),
            Instr.I32Eq,
            Instr.If(Value.I32)
        ) + binders.mapIndexed { ix, b ->
            listOf<Instr>(
                Instr.GetLocal(scrutinee),
                Instr.I32Const(ix),
                Instr.Call(func_index("read_pack_field")),
                Instr.SetLocal(b)
            )
        }.flatten() +
                compile_expr(locals, case.body.instantiate(binders.map(IR.Expression::GetLocal))) +
                listOf<Instr>(Instr.Else) +
                cont +
                listOf(
                    Instr.End
                )
    }
}


fun main() {
    val input =
        """
type Maybe<a> { Nothing(), Just(a) }
type Either<a, b> { Left(a), Right(b) }
type List<a> { Cons(a, List<a>), Nil() }

let isEven : Int -> Bool =
  \x. eq_int (div x 2) 0 in
let fromMaybe : forall a. a -> Maybe<a> -> a =
  \def. \x. match x {
    Maybe::Just(x) -> x,
    Maybe::Nothing() -> def
  } in
let rec map : forall a b. (a -> b) -> List<a> -> List<b> = 
  \f. \ls. match ls {
    List::Nil() -> List::Nil(),
    List::Cons(x, xs) -> List::Cons(f x, map f xs),
  } in
let rec sum : List<Int> -> Int =
  \ls. match ls {
    List::Cons(x, xs) -> add x (sum xs),
    List::Nil() -> 0,
  } in
sum (map (\x. sub x 1) List::Cons(1, List::Cons(2, List::Nil())))
"""

    val input2 =
        """
            let rec fib = 
                \x. if eq_int x 1 
                    then 1 
                    else if eq_int x 2 
                        then 1 
                        else add (fib (sub x 1)) (fib (sub x 2)) in 
            fib 45
        """
    val (tys, expr) = Parser(Lexer(input2)).parseInput()

    val typeMap = TypeMap(HashMap())
    tys.forEach { typeMap.tm.put(it.name, TypeInfo(it.typeVariables, it.dataConstructors)) }

    val typeChecker = TypeChecker(CheckState.initial())
    typeChecker.checkState.typeMap = typeMap
    println("Inferred: ${typeChecker.inferExpr(expr)}")

    val lowering = Lowering(typeMap)
    val prog = lowering.lowerProg(expr)
    prog.forEach { decl -> println(decl.pretty()) }

    val codegen = Codegen()
    codegen.init_rts()
    val mod = codegen.compile_prog(prog)
    val ast = AstToSExpr().fromModule(mod)

    println(ast)
    val buf = ByteArrayOutputStream()
    AstToBinary().fromModule(ByteWriter.OutputStream(buf), mod)
    File("out.wasm").writeBytes(buf.toByteArray())
}
