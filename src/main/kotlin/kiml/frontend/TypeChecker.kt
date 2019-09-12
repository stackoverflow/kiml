package kiml.frontend

import kiml.syntax.*
import kotlin.Exception

data class Substitution(val subst: HashMap<Int, Monotype>) {
    fun apply(ty: Monotype): Monotype =
        when (ty) {
            is Monotype.Unknown ->
                subst[ty.u]?.let(::apply) ?: ty
            is Monotype.Function ->
                Monotype.Function(apply(ty.argument), apply(ty.result))
            is Monotype.Constructor ->
                ty.copy(arguments = ty.arguments.map { apply(it) })
            else -> ty
        }

    override fun toString(): String =
        "{ " + subst.toList().joinToString("\n, ") { (u, ty) -> "u$u ↦ ${ty.pretty()}" } + "\n}"
}

data class Environment(val env: HashMap<Name, Polytype> = hashMapOf()) {
    fun unknowns(): HashSet<Int> {
        val res = HashSet<Int>()
        for ((_, ty) in env) {
            res.union(ty.unknowns())
        }
        return res
    }

    fun insertMono(name: Name, ty: Monotype) {
        env[name] = Polytype(listOf(), ty)
    }
}

data class TypeInfo(val tyArgs: List<TyVar>, val constructors: List<DataConstructor>) {
    companion object {
        val empty = TypeInfo(listOf(), listOf())
    }
}

data class TypeMap(val tm: HashMap<Name, TypeInfo>)

data class CheckState(
    val environment: Environment = {
        val res = Environment()
        res.insertMono(Name("isEven"), Monotype.Function(Monotype.int, Monotype.bool))
        res
    }(),
    val substitution: Substitution = Substitution(HashMap()),
    val typeMap: TypeMap = TypeMap(
        hashMapOf(
            Name("Int") to TypeInfo.empty,
            Name("Bool") to TypeInfo.empty,
            Name("Maybe") to TypeInfo(
                listOf(TyVar("a")),
                listOf(
                    DataConstructor(Name("Nothing"), emptyList()),
                    DataConstructor(Name("Just"), listOf(Monotype.Var(TyVar("a"))))
                )
            )
        )
    ),
    var fresh_supply: Int = 0
)

class TypeChecker {
    private val checkState = CheckState()

    private fun freshUnknown(): Monotype = Monotype.Unknown(++checkState.fresh_supply)

    private fun zonk(ty: Monotype): Monotype = checkState.substitution.apply(ty)

    private fun lookupName(v: Name): Monotype =
        checkState.environment.env[v]?.let(::instantiate) ?: throw Exception("Unknown variable $v")

    private fun lookupType(ty: Name): TypeInfo = checkState.typeMap.tm[ty] ?: throw Exception("Unknown type $ty")

    private fun instantiate(ty: Polytype): Monotype {
        var result = ty.type
        for (v in ty.vars) {
            result = result.subst(v, freshUnknown())
        }
        return result
    }

    private fun generalise(ty: Monotype): Polytype {
        val ty = zonk(ty)
        val niceVars = "abcdefghijklmnopqrstuvwxyz".iterator()
        val quantified: MutableList<TyVar> = mutableListOf()
        val subst: HashMap<Int, Monotype> = HashMap()
        val envUnknowns = checkState.environment.unknowns()
        for (free in ty.unknowns()) {
            if (!envUnknowns.contains(free)) {
                val tyVar = TyVar(niceVars.nextChar().toString())
                quantified.add(tyVar)
                subst[free] = Monotype.Var(tyVar)
            }
        }
        return Polytype(quantified, Substitution(subst).apply(ty))
    }

    private fun <A> bindNamesMono(names: List<Pair<Name, Monotype>>, action: () -> A): A =
        names.fold(action) { k, (name, ty) ->
            { bindNameMono(name, ty, k) }
        }()

    private fun <A> bindNameMono(v: Name, ty: Monotype, action: () -> A): A =
        bindName(v, Polytype(listOf(), ty), action)

    private fun <A> bindName(v: Name, ty: Polytype, action: () -> A): A {
        val prev = checkState.environment.env.put(v, ty)
        val res = action()
        // If the name wasn't previously bound...
        if (prev == null) {
            // we need to remove it from the environment again
            checkState.environment.env.remove(v)
        } else {
            // otherwise we need to restore the previous binding
            checkState.environment.env[v] = prev
        }
        return res
    }

    private fun occursCheck(u: Int, ty: Monotype) {
        if (ty is Monotype.Unknown) return
        if (ty.unknowns().contains(u)) {
            throw Exception("Occurs check failed for u$u and ${zonk(ty).pretty()}")
        }
    }

    private fun solveType(u: Int, ty: Monotype) {
        occursCheck(u, ty)
        checkState.substitution.subst[u] = ty
    }

    private fun unify(ty1: Monotype, ty2: Monotype) {
        val ty1 = zonk(ty1)
        val ty2 = zonk(ty2)
        if (ty1 == ty2) return
        when {
            ty1 is Monotype.Constructor && ty2 is Monotype.Constructor -> {
                if (ty1.name != ty2.name) throw Exception("Failed to unify ${ty1.pretty()} with ${ty2.pretty()}")
                ty1.arguments.zip(ty2.arguments) { t1, t2 ->
                    unify(t1, t2)
                }
            }
            ty1 is Monotype.Unknown -> solveType(ty1.u, ty2)
            ty2 is Monotype.Unknown -> solveType(ty2.u, ty1)
            ty1 is Monotype.Function && ty2 is Monotype.Function -> {
                unify(ty1.argument, ty2.argument)
                unify(ty1.result, ty2.result)
            }
            else -> throw Exception("Failed to unify ${ty1.pretty()} with ${ty2.pretty()}")
        }
    }

    private fun infer(expr: Expression): Monotype {
        return when (expr) {
            is Expression.Int ->
                Monotype.int
            is Expression.Bool ->
                Monotype.bool
            is Expression.Var ->
                lookupName(expr.name)
            is Expression.Lambda -> {
                val tyBinder = freshUnknown()
                val tyBody = bindNameMono(expr.binder, tyBinder) { infer(expr.body) }
                Monotype.Function(tyBinder, tyBody)
            }
            is Expression.App -> {
                val tyResult = freshUnknown()
                val tyFun = infer(expr.function)
                val tyArg = infer(expr.argument)
                unify(tyFun, Monotype.Function(tyArg, tyResult))
                tyResult
            }
            is Expression.Let -> {
                val tyBinder = infer(expr.expr)
                bindName(expr.binder, generalise(tyBinder)) { infer(expr.body) }
            }
            is Expression.If -> {
                val tyCond = infer(expr.condition)
                unify(tyCond, Monotype.bool)
                val tyThen = infer(expr.thenCase)
                val tyElse = infer(expr.elseCase)
                unify(tyThen, tyElse)
                tyThen // Could just as well be tyElse
            }
            is Expression.Match -> {
                val tyExpr = infer(expr.expr)
                val tyRes = freshUnknown()
                expr.cases.forEach {
                    val typedNames = inferPattern(it.pattern, tyExpr)
                    val tyCase = bindNamesMono(typedNames) {
                        infer(it.expr)
                    }
                    unify(tyRes, tyCase)
                }
                tyRes
            }
            is Expression.Construction -> {
                val tyInfo = lookupType(expr.ty)
                val dtor = tyInfo.constructors.find { it.name == expr.dtor }
                        ?: throw Exception("Unknown dtor ${expr.ty}::${expr.dtor}")
                Monotype.int
            }
        }
    }

    private fun inferPattern(pattern: Pattern, ty: Monotype): List<Pair<Name, Monotype>> {
        return when (pattern) {
            is Pattern.Constructor -> {
                val tyInfo = lookupType(pattern.ty)
                val dtor = tyInfo.constructors.find { it.name == pattern.dtor }
                    ?: throw Exception("Unknown dtor ${pattern.dtor}")
                val freshVars = tyInfo.tyArgs.map { it to freshUnknown() }
                unify(ty, Monotype.Constructor(pattern.ty, freshVars.map { it.second }))
                pattern.fields.zip(dtor.args).flatMap { (pat, ty) -> inferPattern(pat, ty.subst_many(freshVars)) }
            }
            is Pattern.Var -> listOf(pattern.v to ty)
        }
    }


    fun inferExpr(expr: Expression): Monotype = zonk(infer(expr)).also { println(checkState.substitution) }
}

fun main() {
//    val expr = Parser(Lexer("let id = (\\x. x) in if id true then id 24 else 10")).parseExpression()

    val expr = Expression.Lambda(
        Name("x"), Expression.Match(
            Expression.Var(Name("x")), listOf(
                Case(
                    Pattern.Constructor(
                        Name("Maybe"),
                        Name("Just"),
                        listOf(Pattern.Constructor(Name("Maybe"), Name("Just"), listOf(Pattern.Var(Name("x")))))
                    ), Expression.Var(Name("x"))
                ),
                Case(Pattern.Constructor(Name("Maybe"), Name("Nothing"), listOf()), Expression.Int(10))
            )
        )
    )
    println("${expr.pretty()} : ")
    println("  ${TypeChecker().inferExpr(expr).pretty()}")
}