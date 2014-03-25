package fiddle
import scala.reflect.macros.Context
import scala.language.experimental.macros

object Macros {

  def doPrint(s: String)(c: Context)(exprs: c.Expr[Any]): c.Expr[Unit] = {
    import c.universe._
    val strExprs = Seq(exprs).map(e => c.resetLocalAttrs(q"""($e).toString"""))
    val name = newTermName(s)
    c.Expr[Unit](q"""Page.$name(..$strExprs)""")
  }
  def printlnProxy(c: Context)(exprs: c.Expr[Any]): c.Expr[Unit] = {
    doPrint("printlnImpl")(c)(exprs)
  }
  def println(exprs: Any): Unit = macro printlnProxy

  def renderlnProxy(c: Context)(exprs: c.Expr[Any]): c.Expr[Unit] = {
    doPrint("renderlnImpl")(c)(exprs)
  }
  def renderln(exprs: Any): Unit = macro renderlnProxy
}
